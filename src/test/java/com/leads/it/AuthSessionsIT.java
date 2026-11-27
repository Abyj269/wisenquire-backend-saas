package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Module 02 P2 (Step 6.1): the JWT subject as user id (flag auth.subject_user_id, old email tokens still accepted) and
 * rotating refresh tokens (flag auth.refresh_tokens): 15-minute access, rotation, reuse ends the login, a changed
 * sessionVersion ends it, logout ends it. Both flags OFF: login answers as before (24 h, no refresh token).
 */
class AuthSessionsIT extends AbstractIntegrationTest {

    static final String PASSWORD = "It-Session-Pass-1";

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    private final ObjectMapper json = new ObjectMapper();
    private Long userId;
    private String email;

    @BeforeEach
    void freshLoginUser() {
        userId = fixture.freshUser(Slot.A1a);
        jdbc.update("UPDATE users SET password = ? WHERE id = ?", encoder.encode(PASSWORD), userId);
        email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    @AfterEach
    void flagsOff() {
        flags.clearOverride(FeatureFlag.AUTH_SUBJECT_USER_ID, fixture.org('A'));
        flags.clearOverride(FeatureFlag.AUTH_REFRESH_TOKENS, fixture.org('A'));
    }

    private void on(FeatureFlag flag) { flags.setOverride(flag, fixture.org('A'), true, "it", "AuthSessionsIT"); }

    private JsonNode login() throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn().getResponse();
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return json.readTree(r.getContentAsString());
    }

    private MockHttpServletResponse refresh(String token) throws Exception {
        return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", token == null ? "" : token)))).andReturn().getResponse();
    }

    private int profile(String accessToken) throws Exception {
        return mvc.perform(get("/auth/profile").header("Authorization", "Bearer " + accessToken)).andReturn().getResponse().getStatus();
    }

    private String subjectOf(String jwt) throws Exception {
        return json.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8)).path("sub").asText();
    }

    @Test
    void bothFlagsOffLoginIsUnchanged() throws Exception {
        JsonNode r = login();
        assertEquals(email, subjectOf(r.path("token").asText()), "email subject, as before");
        assertEquals(86_400_000L, r.path("expiresIn").asLong(), "24 h, as before");
        assertTrue(r.path("refreshToken").isNull() || r.path("refreshToken").isMissingNode(), "no refresh token: " + r);
        assertEquals(200, profile(r.path("token").asText()));
    }

    @Test
    void theSubjectIsTheUserIdAndOldEmailTokensStillWork() throws Exception {
        String oldForm = login().path("token").asText();
        on(FeatureFlag.AUTH_SUBJECT_USER_ID);
        String newForm = login().path("token").asText();
        assertEquals(String.valueOf(userId), subjectOf(newForm), "user-id subject");
        assertEquals(200, profile(newForm));
        assertEquals(200, profile(oldForm), "an email-subject token issued before the switch keeps working");
        assertEquals(200, profile(fixture.token('A', Persona.ORG_ADMIN)));
        MockHttpServletResponse me = mvc.perform(get("/auth/profile").header("Authorization", "Bearer " + newForm)).andReturn().getResponse();
        assertEquals(email, json.readTree(me.getContentAsString()).path("email").asText(), "the same user");
    }

    @Test
    void refreshRotatesAndAReusedTokenEndsTheLogin() throws Exception {
        on(FeatureFlag.AUTH_REFRESH_TOKENS);
        JsonNode first = login();
        assertEquals(900_000L, first.path("expiresIn").asLong(), "15-minute access token");
        assertEquals(604_800_000L, first.path("refreshExpiresIn").asLong(), "7-day refresh token");
        String r1 = first.path("refreshToken").asText();
        assertFalse(r1.isBlank());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, r1), "only a hash is stored");

        MockHttpServletResponse second = refresh(r1);
        assertEquals(200, second.getStatus(), second.getContentAsString());
        JsonNode s = json.readTree(second.getContentAsString());
        String r2 = s.path("refreshToken").asText();
        assertNotEquals(r1, r2, "rotated");
        assertEquals(200, profile(s.path("token").asText()));

        MockHttpServletResponse third = refresh(r2);
        assertEquals(200, third.getStatus());
        String r3 = json.readTree(third.getContentAsString()).path("refreshToken").asText();

        // r1 was replaced more than the two-tab grace ago: presenting it again is a theft signal.
        jdbc.update("UPDATE refresh_tokens SET revoked_at = revoked_at - INTERVAL 1 MINUTE WHERE user_id = ? AND replaced_by IS NOT NULL", userId);
        MockHttpServletResponse reuse = refresh(r1);
        assertEquals(401, reuse.getStatus());
        assertEquals("SESSION_REVOKED", json.readTree(reuse.getContentAsString()).path("code").asText());
        assertEquals(401, refresh(r3).getStatus(), "the whole login family ended, the newest token too");
    }

    @Test
    void twoTabsRefreshingAtOnceKeepTheLogin() throws Exception {
        on(FeatureFlag.AUTH_REFRESH_TOKENS);
        String r1 = login().path("refreshToken").asText();
        assertEquals(200, refresh(r1).getStatus());
        MockHttpServletResponse otherTab = refresh(r1);
        assertEquals(200, otherTab.getStatus(), "a second refresh with the same token within the grace is not treated as theft");
        assertEquals(200, refresh(json.readTree(otherTab.getContentAsString()).path("refreshToken").asText()).getStatus());
    }

    @Test
    void aChangedSessionVersionEndsTheLoginAtTheNextRefresh() throws Exception {
        on(FeatureFlag.AUTH_REFRESH_TOKENS);
        JsonNode r = login();
        jdbc.update("UPDATE users SET session_version = session_version + 1 WHERE id = ?", userId); // e.g. a role edit
        assertEquals(401, profile(r.path("token").asText()), "the access token is refused at once (sessionVersion)");
        assertEquals(401, refresh(r.path("refreshToken").asText()).getStatus(), "and the refresh token cannot renew it");
    }

    @Test
    void logoutEndsTheLoginAndUnknownTokensAreRefused() throws Exception {
        on(FeatureFlag.AUTH_REFRESH_TOKENS);
        String r1 = login().path("refreshToken").asText();
        assertEquals(204, mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", r1)))).andReturn().getResponse().getStatus());
        assertEquals(401, refresh(r1).getStatus(), "logged out");
        assertEquals(401, refresh("not-a-real-token").getStatus());
        assertEquals(401, refresh(null).getStatus());
        assertEquals(204, mvc.perform(post("/auth/logout")).andReturn().getResponse().getStatus(), "logout without a token is harmless");
    }

    @Test
    void refreshIsRefusedOnceTheOrgTurnsTheFlagOff() throws Exception {
        on(FeatureFlag.AUTH_REFRESH_TOKENS);
        String r1 = login().path("refreshToken").asText();
        flags.clearOverride(FeatureFlag.AUTH_REFRESH_TOKENS, fixture.org('A'));
        assertEquals(401, refresh(r1).getStatus(), "rollback: with the flag off the org is back on 24 h logins");
    }
}
