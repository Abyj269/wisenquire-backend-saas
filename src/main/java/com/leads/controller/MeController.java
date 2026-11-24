package com.leads.controller;

import com.leads.config.TenantContext;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;

/** The caller's own context. Takes no ids: the org always comes from the verified JWT. */
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
@Tag(name = "Me", description = "The signed-in user's own context")
public class MeController {

    private final FeatureFlags flags;

    @GetMapping("/feature-flags")
    @Operation(summary = "Resolved feature flags", description = "D13: the exposed flags for the caller's organization (override, else default).")
    public Map<String, Boolean> featureFlags() {
        Long orgId = TenantContext.get().map(TenantContext.Data::organizationId).orElse(null);
        Map<FeatureFlag, Boolean> resolved = flags.resolved(orgId);
        Map<String, Boolean> out = new TreeMap<>();
        for (FeatureFlag f : FeatureFlag.values()) if (f.exposed) out.put(f.key, resolved.getOrDefault(f, false));
        return out;
    }
}
