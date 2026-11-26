package com.leads.it.matrix;

import com.leads.it.fixture.Persona;
import com.leads.model.entity.WhatsAppHoliday;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.BRANCH;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY_WIDE;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 14 P1: the 8 WhatsAppScheduleController endpoints. Business hours are per branch; holidays
 * are checked against the company and every branch they apply to; the holiday list and the auto-reply
 * save are company-wide. All need COMPANY_BRANCH_SETTINGS, which the fixture agents lack (CFG_SCOPE).
 * Writes keep the fixture inert: every day closed, every auto-reply off.
 */
final class WhatsAppScheduleRows {

    private static final Persona[] MANAGERS = {Persona.ORG_ADMIN, Persona.COMPANY_MGR, Persona.BRANCH_MGR};
    private static final Persona[] AGENTS = {Persona.AGENT_OWN_1, Persona.AGENT_OWN_2};

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("GET /whatsapp/business-hours", BRANCH, (f, s, p) -> get("/whatsapp/business-hours").param("branchId", str(f.branch(s))))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("PUT /whatsapp/business-hours", BRANCH, (f, s, p) -> withJson(put("/whatsapp/business-hours"),
                        json("branchIds", List.of(f.branch(s)), "days", closedWeek())))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("GET /whatsapp/holidays", COMPANY_WIDE, (f, s, p) -> get("/whatsapp/holidays").param("companyId", str(f.company(s))))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("POST /whatsapp/holidays", BRANCH, (f, s, p) -> withJson(post("/whatsapp/holidays"), holiday(f.company(s), f.branch(s))))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("PUT /whatsapp/holidays/{id}", BRANCH, (f, s, p) -> {
                    Long id = f.freshHoliday(s);
                    p.watch(() -> f.state(WhatsAppHoliday.class, id, h -> h.getName() + "|" + h.getEndDate()));
                    return withJson(put("/whatsapp/holidays/{id}", id), holiday(f.company(s), f.branch(s)));
                }).onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("DELETE /whatsapp/holidays/{id}", BRANCH, (f, s, p) -> {
                    Long id = f.freshHoliday(s);
                    p.watch(() -> String.valueOf(f.exists(WhatsAppHoliday.class, id)));
                    return delete("/whatsapp/holidays/{id}", id);
                }).onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("GET /whatsapp/auto-reply-settings", COMPANY, (f, s, p) -> get("/whatsapp/auto-reply-settings").param("companyId", str(f.company(s))))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("PUT /whatsapp/auto-reply-settings", COMPANY_WIDE, (f, s, p) -> withJson(
                        put("/whatsapp/auto-reply-settings").param("companyId", str(f.company(s))),
                        json("workingHoursEnabled", false, "nonWorkingHoursEnabled", false, "holidayEnabled", false)))
                        .onlyFor(MANAGERS).deniedFor(AGENTS));
    }

    private static String holiday(Long companyId, Long branchId) {
        return json("companyId", companyId, "name", "IT Matrix Holiday", "startDate", "2030-02-01", "endDate", "2030-02-03",
                "branchIds", List.of(branchId));
    }

    private static List<Map<String, Object>> closedWeek() {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("dayOfWeek", d);
            day.put("isOpen", false);
            days.add(day);
        }
        return days;
    }

    private WhatsAppScheduleRows() { }
}
