package com.leads.it.matrix;

import com.leads.it.fixture.Slot;
import com.leads.it.fixture.TenantFixture;
import com.leads.model.entity.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY_WIDE;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 04 P1: all 24 LeadSettingsController endpoints. Reads are COMPANY scope (every role needs
 * them for lead forms). Writes are COMPANY_WIDE (F-12): with flag lead_settings.company_wide_writes
 * ON ({@link Matrix#FLAGS_ON}) the branch manager and the agents are refused on their own company
 * (X_COMPANY_WIDE rows), even though they hold LEAD_SETTINGS rights. Writes use fresh records with probes.
 */
final class LeadSettingsRows {

    /**
     * One settings resource: its path, a fresh record, the record's watched state, and create/update bodies.
     * The update endpoints copy every field from the body (a missing isActive / isDefault / colorCode is a
     * 500 on the NOT NULL column, as from any client), so the update bodies send the record's current values.
     */
    private record Resource<T>(String path, Class<T> type, BiFunction<TenantFixture, Slot, Long> fresh,
                               Function<T, Object> state, Function<TenantFixture, String> createBody, Function<T, String> updateBody) { }

    static List<EndpointSpec> specs() {
        List<EndpointSpec> all = new ArrayList<>();
        crud(all, new Resource<>("statuses", LeadStatus.class, TenantFixture::freshStatus,
                st -> st.getStatusName() + "|" + st.getIsActive() + "|" + st.getSortOrder(),
                f -> json("statusName", "IT New Status", "statusCode", "IT_NEW_" + unique(f), "colorCode", "#10B981", "stageType", "NEGOTIATION"),
                st -> json("statusName", "IT Renamed Status", "statusCode", st.getStatusCode(), "colorCode", "#10B981", "stageType", "NEGOTIATION",
                        "isActive", st.getIsActive(), "sortOrder", st.getSortOrder(), "isDefault", st.getIsDefault())));
        crud(all, new Resource<>("purposes", LeadPurpose.class, TenantFixture::freshPurpose,
                p -> p.getPurposeName() + "|" + p.getIsActive(),
                // Inactive: an active purpose would make "Lead Purpose" mandatory for the other modules' lead rows.
                f -> json("purposeName", "IT New Purpose " + unique(f), "isActive", false),
                pu -> json("purposeName", "IT Renamed Purpose", "isActive", false, "sortOrder", pu.getSortOrder())));
        crud(all, new Resource<>("lost-reasons", LeadLostReason.class, TenantFixture::freshLostReason,
                r -> r.getReasonText() + "|" + r.getIsActive(),
                f -> json("reasonText", "IT New Reason " + unique(f)),
                lr -> json("reasonText", "IT Renamed Reason", "isActive", lr.getIsActive(), "sortOrder", lr.getSortOrder())));
        crud(all, new Resource<>("sources", LeadSource.class, TenantFixture::freshSource,
                so -> so.getSourceName() + "|" + so.getIsActive(),
                f -> json("sourceName", "IT New Source", "sourceCode", "IT_NEW_SRC_" + unique(f)),
                so -> json("sourceName", "IT Renamed Source", "sourceCode", so.getSourceCode(), "colorCode", so.getColorCode(), "isActive", so.getIsActive(), "sortOrder", so.getSortOrder())));
        crud(all, new Resource<>("dynamic-fields", DynamicFieldDefinition.class, TenantFixture::freshDynamicField,
                d -> d.getFieldLabel() + "|" + d.getIsActive() + "|" + d.getIsMandatory(),
                f -> json("fieldName", "it_new_" + unique(f), "fieldLabel", "IT New Field", "fieldType", "TEXT"),
                d -> json("fieldName", d.getFieldName(), "fieldLabel", "IT Renamed Field", "fieldType", "TEXT", "isActive", d.getIsActive(), "sortOrder", d.getSortOrder())));

        // ---- predefined fields ------------------------------------------------------------------
        all.add(EndpointSpec.of("GET /settings/predefined-fields", COMPANY,
                (f, s, p) -> get("/settings/predefined-fields").param("companyId", str(f.company(s)))));
        all.add(EndpointSpec.of("PUT /settings/predefined-fields/{fieldKey}", COMPANY_WIDE, (f, s, p) -> {
            Long company = f.company(s);
            p.watch(() -> f.predefinedFieldState(company, "EMAIL"));
            return withJson(put("/settings/predefined-fields/{fieldKey}", "EMAIL").param("companyId", str(company)),
                    json("isVisible", true, "isMandatory", false));
        }));

        // ---- company lead settings ---------------------------------------------------------------
        all.add(EndpointSpec.of("GET /settings/company-lead-settings", COMPANY,
                (f, s, p) -> get("/settings/company-lead-settings").param("companyId", str(f.company(s)))));
        all.add(EndpointSpec.of("PUT /settings/company-lead-settings", COMPANY_WIDE, (f, s, p) -> {
            Long company = f.company(s);
            p.watch(() -> f.companyLeadSettingsState(company));
            // The entity defaults, so the ALLOWED rows leave the shared company as it was.
            return withJson(put("/settings/company-lead-settings").param("companyId", str(company)),
                    json("linkProductEnabled", true, "autoAssignEnabled", false));
        }));
        return all;
    }

    private static <T> void crud(List<EndpointSpec> all, Resource<T> r) {
        String base = "/settings/" + r.path();
        all.add(EndpointSpec.of("GET " + base, COMPANY, (f, s, p) -> get(base).param("companyId", str(f.company(s)))));
        all.add(EndpointSpec.of("POST " + base, COMPANY_WIDE,
                (f, s, p) -> withJson(post(base).param("companyId", str(f.company(s))), r.createBody().apply(f))));
        all.add(EndpointSpec.of("PUT " + base + "/{id}", COMPANY_WIDE, (f, s, p) -> {
            Long id = r.fresh().apply(f, s);
            p.watch(() -> f.state(r.type(), id, r.state()));
            return withJson(put(base + "/{id}", id), f.state(r.type(), id, e -> r.updateBody().apply(e)));
        }));
        all.add(EndpointSpec.of("DELETE " + base + "/{id}", COMPANY_WIDE, (f, s, p) -> {
            Long id = r.fresh().apply(f, s);
            p.watch(() -> f.exists(r.type(), id) ? f.state(r.type(), id, r.state()) : "deleted");
            return delete(base + "/{id}", id);
        }));
    }

    private static int unique(TenantFixture f) { return Integer.parseInt(f.phone().substring(6)); }

    private LeadSettingsRows() { }
}
