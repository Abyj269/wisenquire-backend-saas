package com.leads.it.matrix;

import java.util.List;

import static com.leads.it.matrix.EndpointSpec.Scope.SELF;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The caller's own context (01 Part B item 4). Takes no ids, so there is nothing to point at
 * another tenant: ANON and ALLOWED rows only. FeatureFlagsIT checks that org B's overrides never
 * appear in org A's answer.
 */
final class MeRows {

    static List<EndpointSpec> specs() {
        return List.of(EndpointSpec.of("GET /me/feature-flags", SELF, (f, s, p) -> get("/me/feature-flags")));
    }

    private MeRows() { }
}
