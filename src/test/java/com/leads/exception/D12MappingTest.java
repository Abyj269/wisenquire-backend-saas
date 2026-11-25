package com.leads.exception;

import com.leads.config.TenantContext;
import com.leads.entitlement.EntitlementLimitException;
import com.leads.entitlement.Feature;
import com.leads.entitlement.FeatureNotInPlanException;
import com.leads.entitlement.Metric;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 01 §7 "D12 mapping": each exception gives the expected code and status, every body is
 * {code, message, details, traceId} with the old message key, and missing vs foreign records are
 * 400/403 with the flag off and both 404 with flag errors.uniform_not_found on.
 */
class D12MappingTest {

    private static final Map<String, Supplier<RuntimeException>> THROWERS = Map.ofEntries(
            Map.entry("missing", () -> new RecordNotFoundException("Record not found.")),
            Map.entry("foreign", () -> new ScopeDeniedException("This record is outside your permitted scope.")),
            Map.entry("permission", () -> new AccessDeniedException("nope")),
            Map.entry("illegalArgument", () -> new IllegalArgumentException("Phone number is required")),
            Map.entry("illegalState", () -> new IllegalStateException("Broadcast is already COMPLETED.")),
            Map.entry("optimistic", () -> new org.springframework.dao.OptimisticLockingFailureException("v")),
            Map.entry("rateLimited", () -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please retry later.")),
            Map.entry("statusConflict", () -> new ResponseStatusException(HttpStatus.CONFLICT, "24-hour window closed")),
            Map.entry("whatsapp", () -> new WhatsAppApiException("Meta said no")),
            Map.entry("loginNotAllowed", () -> new LoginNotAllowedException("Login is not permitted from this location")),
            Map.entry("suspended", () -> new ApiException(ErrorCode.TENANT_SUSPENDED, "This account is suspended.")),
            Map.entry("featureNotInPlan", () -> new FeatureNotInPlanException(Feature.AI)),
            Map.entry("limit", () -> new EntitlementLimitException(Metric.USERS, 5, 5)),
            Map.entry("boom", () -> new NullPointerException("internal detail")));

    @RestController
    static class Thrower {
        @GetMapping("/throw/{name}")
        void raise(@PathVariable String name) { throw THROWERS.get(name).get(); }

        @GetMapping("/needs-param")
        void needsParam(@org.springframework.web.bind.annotation.RequestParam Long companyId) { }
    }

    static Stream<Arguments> mappings() {
        return Stream.of(
                Arguments.of("missing", 400, "VALIDATION_FAILED"),
                Arguments.of("foreign", 403, "FORBIDDEN"),
                Arguments.of("permission", 403, "FORBIDDEN"),
                Arguments.of("illegalArgument", 400, "VALIDATION_FAILED"),
                Arguments.of("illegalState", 409, "CONFLICT"),
                Arguments.of("optimistic", 409, "CONFLICT"),
                Arguments.of("rateLimited", 429, "RATE_LIMITED"),
                Arguments.of("statusConflict", 409, "CONFLICT"),
                Arguments.of("whatsapp", 502, "UPSTREAM_ERROR"),
                Arguments.of("loginNotAllowed", 403, "FORBIDDEN"),
                Arguments.of("suspended", 423, "TENANT_SUSPENDED"),
                Arguments.of("featureNotInPlan", 403, "FEATURE_NOT_IN_PLAN"),
                Arguments.of("limit", 409, "ENTITLEMENT_LIMIT"),
                Arguments.of("boom", 500, "INTERNAL_ERROR"));
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @ParameterizedTest(name = "{0} -> {1} {2} (flag off)")
    @MethodSource("mappings")
    void eachExceptionHasItsCodeAndStatus(String name, int status, String code) throws Exception {
        mvc(false).perform(get("/throw/" + name))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details").isMap())
                .andExpect(jsonPath("$.traceId").hasJsonPath());
    }

    @Test
    void malformedRequestsAre400Not500() throws Exception {
        mvc(false).perform(get("/needs-param")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc(false).perform(get("/needs-param").param("companyId", "not-a-number")).andExpect(status().isBadRequest());
        mvc(false).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/needs-param")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void internalDetailsNeverReachTheClient() throws Exception {
        mvc(false).perform(get("/throw/boom")).andExpect(jsonPath("$.message").value("Something went wrong. Please try again."));
    }

    @Test
    void rateLimitedCarriesRetryAfter() throws Exception {
        mvc(false).perform(get("/throw/rateLimited")).andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void entitlementDetailsAreStructured() throws Exception {
        mvc(false).perform(get("/throw/limit"))
                .andExpect(jsonPath("$.details.metric").value("users"))
                .andExpect(jsonPath("$.details.limit").value(5))
                .andExpect(jsonPath("$.details.current").value(5));
    }

    @Test
    void withTheFlagOnMissingAndForeignAreTheSame404() throws Exception {
        TenantContext.set(new TenantContext.Data(1L, 7L, "ORGANIZATION"));
        MockMvc mvc = mvc(true);
        for (String name : new String[]{"missing", "foreign"}) {
            mvc.perform(get("/throw/" + name))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Record not found."));
        }
        // A permission denial is not hidden: still 403 with the flag on.
        mvc.perform(get("/throw/permission")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private static MockMvc mvc(boolean uniformNotFound) {
        FeatureFlags flags = mock(FeatureFlags.class);
        when(flags.isOn(eq(FeatureFlag.ERRORS_UNIFORM_NOT_FOUND), any())).thenReturn(uniformNotFound);
        @SuppressWarnings("unchecked")
        ObjectProvider<FeatureFlags> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(flags);
        return MockMvcBuilders.standaloneSetup(new Thrower()).setControllerAdvice(new GlobalExceptionHandler(provider)).build();
    }
}
