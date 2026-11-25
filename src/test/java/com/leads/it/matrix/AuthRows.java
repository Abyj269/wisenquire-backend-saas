package com.leads.it.matrix;

import java.util.List;

import static com.leads.it.matrix.EndpointSpec.Scope.SELF;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Module 02 P1: the two authenticated AuthController endpoints. Both act only on the caller
 * (no ids), so ANON and ALLOWED rows; AuthProfileIT checks the profile is always the caller's own.
 * POST /auth/login and /auth/verify-otp are public (exempt-endpoints.txt).
 */
final class AuthRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("GET /auth/profile", SELF, (f, s, p) -> get("/auth/profile")),
                EndpointSpec.of("POST /auth/getting-started/seen", SELF, (f, s, p) -> post("/auth/getting-started/seen")));
    }

    private AuthRows() { }
}
