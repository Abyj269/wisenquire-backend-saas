package com.leads.it.matrix;

import com.leads.model.entity.DeliveryFailureReason;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY_WIDE;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 09 P1: the 6 DeliverySettingsController endpoints. Reads COMPANY; writes COMPANY_WIDE (F-12,
 * flag delivery_settings.company_wide_writes ON in the matrix): the branch manager and the agents are
 * refused on their own company.
 */
final class DeliverySettingsRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("GET /settings/delivery-failure-reasons", COMPANY,
                        (f, s, p) -> get("/settings/delivery-failure-reasons").param("companyId", str(f.company(s)))),
                EndpointSpec.of("POST /settings/delivery-failure-reasons", COMPANY_WIDE, (f, s, p) -> withJson(
                        post("/settings/delivery-failure-reasons").param("companyId", str(f.company(s))), json("reasonText", "IT new reason"))),
                EndpointSpec.of("PUT /settings/delivery-failure-reasons/{id}", COMPANY_WIDE, (f, s, p) -> {
                    Long id = f.freshFailureReason(s);
                    p.watch(() -> f.state(DeliveryFailureReason.class, id, r -> r.getReasonText() + "|" + r.getIsActive()));
                    return withJson(put("/settings/delivery-failure-reasons/{id}", id), json("reasonText", "IT renamed reason"));
                }),
                EndpointSpec.of("DELETE /settings/delivery-failure-reasons/{id}", COMPANY_WIDE, (f, s, p) -> {
                    Long id = f.freshFailureReason(s);
                    p.watch(() -> String.valueOf(f.exists(DeliveryFailureReason.class, id)));
                    return delete("/settings/delivery-failure-reasons/{id}", id);
                }),
                EndpointSpec.of("GET /settings/delivery-settings", COMPANY,
                        (f, s, p) -> get("/settings/delivery-settings").param("companyId", str(f.company(s)))),
                // Sends the fixture's values: a missing deliveryModuleEnabled would switch the module OFF.
                EndpointSpec.of("PUT /settings/delivery-settings", COMPANY_WIDE, (f, s, p) -> withJson(
                        put("/settings/delivery-settings").param("companyId", str(f.company(s))),
                        json("deliveryModuleEnabled", true, "requireApprovalForPostpone", true, "requireApprovalForClose", true))));
    }

    private DeliverySettingsRows() { }
}
