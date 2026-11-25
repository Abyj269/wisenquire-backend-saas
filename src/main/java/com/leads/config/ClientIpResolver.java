package com.leads.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The real client IP for security decisions: login rate limits, branch IP restrictions and the
 * trusted-user OTP skip (AuthService), and the audit log.
 * <p>
 * Security hotfix (module 02, finding 2026-11-24). {@code request.getRemoteAddr()} cannot be used:
 * with {@code server.forward-headers-strategy=framework} Spring rewrites it from the LEFT-most
 * X-Forwarded-For entry, which any client can send. Instead:
 * <ol>
 *   <li>take the TCP peer of the connection (the innermost request, before any wrapper);</li>
 *   <li>if the peer is not a trusted proxy, it is the client and X-Forwarded-For is ignored;</li>
 *   <li>if it is a trusted proxy, walk X-Forwarded-For from the RIGHT (the entries proxies append)
 *       and return the first address that is not itself a trusted proxy.</li>
 * </ol>
 * Trusted proxies: {@code app.security.trusted-proxies} (CIDRs). The default (loopback and private
 * ranges) fits nginx on the same host or in the same Docker network. A proxy on a public address
 * must be listed, or every user would share the proxy's IP. Only IP literals are accepted from the
 * header (never resolved via DNS).
 */
@Component
public class ClientIpResolver {

    public static final String DEFAULT_TRUSTED = "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7";

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV4_WITH_PORT = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d{1,5}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final List<IpAddressMatcher> trusted;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:" + DEFAULT_TRUSTED + "}") List<String> trustedProxies) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String cidr : trustedProxies) {
            String c = cidr == null ? "" : cidr.trim();
            if (!c.isEmpty()) matchers.add(new IpAddressMatcher(c));
        }
        this.trusted = Collections.unmodifiableList(matchers);
    }

    public String resolve(HttpServletRequest request) {
        HttpServletRequest raw = innermost(request);
        String peer = normalize(raw.getRemoteAddr());
        if (peer == null) return raw.getRemoteAddr();
        if (!isTrusted(peer)) return peer;
        List<String> chain = new ArrayList<>();
        var headers = raw.getHeaders("X-Forwarded-For");
        while (headers != null && headers.hasMoreElements()) {
            for (String part : headers.nextElement().split(",")) chain.add(part.trim());
        }
        String leftmostValid = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            String ip = normalize(chain.get(i));
            if (ip == null) break; // garbage: stop trusting anything further left
            if (!isTrusted(ip)) return ip;
            leftmostValid = ip;
        }
        // Every entry is a trusted (internal) address, or there is no usable header.
        return leftmostValid != null ? leftmostValid : peer;
    }

    boolean isTrusted(String ip) {
        for (IpAddressMatcher m : trusted) {
            try { if (m.matches(ip)) return true; } catch (IllegalArgumentException ignored) { /* other family */ }
        }
        return false;
    }

    /** An IP literal without port/brackets, or null if the value is not an IP literal (never a DNS name). */
    static String normalize(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || v.length() > 64) return null;
        var withPort = IPV4_WITH_PORT.matcher(v);
        if (withPort.matches()) v = withPort.group(1);
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end < 0) return null;
            v = v.substring(1, end);
        }
        var m4 = IPV4.matcher(v);
        if (m4.matches()) {
            for (int g = 1; g <= 4; g++) if (Integer.parseInt(m4.group(g)) > 255) return null;
            return v;
        }
        return v.contains(":") && IPV6.matcher(v).matches() ? v.toLowerCase() : null;
    }

    private static HttpServletRequest innermost(HttpServletRequest request) {
        HttpServletRequest r = request;
        while (r instanceof HttpServletRequestWrapper w && w.getRequest() instanceof HttpServletRequest inner) r = inner;
        return r;
    }
}
