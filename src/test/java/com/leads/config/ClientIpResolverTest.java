package com.leads.config;

import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Security hotfix (module 02): the client IP cannot be chosen by the client through X-Forwarded-For. */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(Arrays.asList(ClientIpResolver.DEFAULT_TRUSTED.split(",")));

    private static MockHttpServletRequest from(String peer, String... forwardedFor) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(peer);
        for (String h : forwardedFor) r.addHeader("X-Forwarded-For", h);
        return r;
    }

    @Test
    void aDirectClientCannotSpoofItsAddress() {
        assertEquals("198.51.100.7", resolver.resolve(from("198.51.100.7", "203.0.113.99")));
        assertEquals("198.51.100.7", resolver.resolve(from("198.51.100.7", "10.0.0.1"))); // not even a "trusted" one
    }

    @Test
    void behindATrustedProxyTheRightMostUntrustedEntryWins() {
        // nginx appends the real peer: "<whatever the client sent>, <real client>"
        assertEquals("198.51.100.7", resolver.resolve(from("127.0.0.1", "203.0.113.99, 198.51.100.7")));
        // a chain of internal proxies after the client
        assertEquals("198.51.100.7", resolver.resolve(from("172.18.0.3", "203.0.113.99, 198.51.100.7, 10.0.0.5")));
        // several header lines are one list
        assertEquals("198.51.100.7", resolver.resolve(from("127.0.0.1", "203.0.113.99", "198.51.100.7")));
    }

    @Test
    void aTrustedProxyWithoutAHeaderIsTheClient() {
        assertEquals("127.0.0.1", resolver.resolve(from("127.0.0.1")));
    }

    @Test
    void anInternalOnlyChainReturnsTheOriginalInternalClient() {
        assertEquals("10.1.2.3", resolver.resolve(from("127.0.0.1", "10.1.2.3, 10.0.0.5")));
    }

    @Test
    void garbageStopsTheWalkAndNothingIsResolvedThroughDns() {
        // "evil.example" must not be resolved or returned; the walk stops there and takes nothing to its left.
        assertEquals("127.0.0.1", resolver.resolve(from("127.0.0.1", "203.0.113.99, evil.example")));
        assertEquals("198.51.100.7", resolver.resolve(from("127.0.0.1", "evil.example, 198.51.100.7")));
        assertEquals("127.0.0.1", resolver.resolve(from("127.0.0.1", "999.1.1.1")));
        assertNull(ClientIpResolver.normalize("localhost"));
        assertNull(ClientIpResolver.normalize("1.2.3.4.example.com"));
    }

    @Test
    void portsBracketsAndIpv6() {
        assertEquals("198.51.100.7", ClientIpResolver.normalize("198.51.100.7:5432"));
        assertEquals("2001:db8::1", ClientIpResolver.normalize("[2001:DB8::1]:443"));
        assertEquals("2001:db8::1", resolver.resolve(from("::1", "2001:db8::1")));
        assertEquals("2001:db8::7", resolver.resolve(from("2001:db8::7", "198.51.100.1")));
    }

    @Test
    void wrappersAreLookedThroughToTheRealConnection() {
        MockHttpServletRequest raw = from("198.51.100.7", "203.0.113.99");
        HttpServletRequestWrapper rewritten = new HttpServletRequestWrapper(raw) {
            @Override public String getRemoteAddr() { return "203.0.113.99"; } // what ForwardedHeaderFilter does
        };
        assertEquals("198.51.100.7", resolver.resolve(rewritten));
    }

    @Test
    void aPublicProxyMustBeConfigured() {
        ClientIpResolver withPublicProxy = new ClientIpResolver(List.of("127.0.0.1/32", "198.51.100.10/32"));
        assertEquals("203.0.113.5", withPublicProxy.resolve(from("198.51.100.10", "203.0.113.5")));
        assertEquals("198.51.100.10", resolver.resolve(from("198.51.100.10", "203.0.113.5")), "not listed = it is the client");
    }
}
