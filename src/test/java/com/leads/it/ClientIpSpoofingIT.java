package com.leads.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Security hotfix (module 02, finding 2026-11-24), end to end through the real filter chain
 * (incl. the forward-headers handling): a spoofed X-Forwarded-For no longer bypasses the login
 * IP rate limit (30/min). Before the fix, 10/10 spoofed attempts from an over-limit IP got 401.
 */
class ClientIpSpoofingIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private int login(String peer, String forwardedFor, int n) throws Exception {
        MockHttpServletRequestBuilder req = post("/auth/login").contentType("application/json")
                .content("{\"email\":\"spoof" + peer.hashCode() + "-" + n + "@probe.invalid\",\"password\":\"wrong\"}")
                .with(r -> { r.setRemoteAddr(peer); return r; });
        if (forwardedFor != null) req.header("X-Forwarded-For", forwardedFor);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    @Test
    void aDirectClientCannotEscapeTheIpLimitWithAFakeHeader() throws Exception {
        String attacker = "198.51.100.41";
        for (int i = 0; i < 30; i++) assertEquals(401, login(attacker, "203.0.113." + i, i), "attempt " + (i + 1));
        // Over the limit: a fresh fake address per request must not help.
        for (int i = 30; i < 35; i++) assertEquals(429, login(attacker, "203.0.113." + i, i), "spoofed attempt " + (i + 1));
    }

    @Test
    void behindTheProxyTheRealClientIsLimitedNotTheFakeLeftEntry() throws Exception {
        // nginx (127.0.0.1) appends the real client: "<fake>, <real>"
        for (int i = 0; i < 30; i++) assertEquals(401, login("127.0.0.1", "203.0.114." + i + ", 198.51.100.42", 100 + i));
        assertEquals(429, login("127.0.0.1", "203.0.114.250, 198.51.100.42", 200), "same real client, new fake prefix");
        // A different real client behind the same proxy is not affected.
        assertEquals(401, login("127.0.0.1", "203.0.114.251, 198.51.100.43", 201));
    }
}
