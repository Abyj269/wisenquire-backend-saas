package com.leads.it.matrix;

import com.leads.it.fixture.Persona;
import com.leads.model.entity.*;

import java.util.List;
import java.util.Map;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.*;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 03 P1: all 21 TenantController endpoints. Writes use fresh records (a fresh branch in a
 * home slot is granted to that org's branch-level personas), and every denied write must leave its
 * record unchanged (probes). Agents hold no tenant-admin rights, like the real "Agent" template, so
 * the admin endpoints get CFG_SCOPE rows for them. Tenant rows run last among the ALLOWED rows, so
 * the no-op updates of shared org/company records cannot disturb other modules' rows.
 */
final class TenantRows {

    private static final Persona[] MANAGERS = {Persona.ORG_ADMIN, Persona.COMPANY_MGR, Persona.BRANCH_MGR};
    private static final Persona[] AGENTS = {Persona.AGENT_OWN_1, Persona.AGENT_OWN_2};
    private static final List<Map<String, Object>> LEADS_VIEW = List.of(Map.of("moduleName", "LEADS", "canView", true));

    static List<EndpointSpec> specs() {
        return List.of(
                // ---- organizations ------------------------------------------------------------
                EndpointSpec.of("GET /tenant/organizations", PLATFORM, (f, s, p) -> get("/tenant/organizations")),
                EndpointSpec.of("POST /tenant/organizations", PLATFORM, (f, s, p) -> withJson(post("/tenant/organizations"),
                        json("name", "IT Should Not Exist", "code", "IT-NOPE-" + unique(f)))),
                EndpointSpec.of("PUT /tenant/organizations/{id}", ORG, (f, s, p) -> {
                    Long org = f.org(s.org());
                    p.watch(() -> f.state(Organization.class, org, o -> o.getName() + "|" + o.getCode() + "|" + o.getIsActive() + "|" + o.getUpdatedAt()));
                    return withJson(put("/tenant/organizations/{id}", org), json(
                            "name", f.state(Organization.class, org, Organization::getName),
                            "code", f.state(Organization.class, org, Organization::getCode),
                            "logoUrl", f.state(Organization.class, org, Organization::getLogoUrl)));
                }).onlyFor(Persona.ORG_ADMIN).deniedFor(Persona.COMPANY_MGR, Persona.BRANCH_MGR),
                EndpointSpec.of("GET /tenant/organizations/{id}/summary", ORG,
                        (f, s, p) -> get("/tenant/organizations/{id}/summary", f.org(s.org())))
                        .onlyFor(Persona.ORG_ADMIN).deniedFor(Persona.COMPANY_MGR, Persona.BRANCH_MGR, Persona.AGENT_OWN_1),

                // ---- companies -------------------------------------------------------------------
                EndpointSpec.of("GET /tenant/companies", ORG,
                        (f, s, p) -> get("/tenant/companies").param("organizationId", str(f.org(s.org()))))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("POST /tenant/companies", ORG, (f, s, p) -> withJson(post("/tenant/companies"), json(
                        "organizationId", f.org(s.org()), "name", "IT New Company", "code", "IT-NC-" + unique(f), "timezone", "Asia/Dubai")))
                        .onlyFor(Persona.ORG_ADMIN).deniedFor(Persona.COMPANY_MGR, Persona.BRANCH_MGR),
                EndpointSpec.of("PUT /tenant/companies/{id}", COMPANY_WIDE, (f, s, p) -> {
                    Long company = f.company(s);
                    p.watch(() -> f.state(Company.class, company, c -> c.getName() + "|" + c.getTimezone() + "|" + c.getIsActive() + "|" + c.getUpdatedAt()));
                    return withJson(put("/tenant/companies/{id}", company), json(
                            "name", f.state(Company.class, company, Company::getName),
                            "code", f.state(Company.class, company, Company::getCode),
                            "taxNumber", f.state(Company.class, company, Company::getTaxNumber),
                            "logoUrl", f.state(Company.class, company, Company::getLogoUrl),
                            "timezone", f.state(Company.class, company, Company::getTimezone)));
                }).onlyFor(MANAGERS),

                // ---- branches --------------------------------------------------------------------
                EndpointSpec.of("GET /tenant/branches", COMPANY,
                        (f, s, p) -> get("/tenant/branches").param("companyId", str(f.company(s)))),
                EndpointSpec.of("POST /tenant/branches", COMPANY_WIDE, (f, s, p) -> withJson(post("/tenant/branches"), json(
                        "companyId", f.company(s), "name", "IT New Branch", "code", "IT-NB-" + unique(f))))
                        .onlyFor(MANAGERS),
                EndpointSpec.of("PUT /tenant/branches/{id}", BRANCH, (f, s, p) -> {
                    Long branch = f.freshBranch(s, true);
                    p.watch(() -> f.state(Branch.class, branch, b -> b.getName() + "|" + b.getIsActive() + "|" + b.getUpdatedAt()));
                    return withJson(put("/tenant/branches/{id}", branch), json("companyId", f.company(s), "name", "IT Renamed Branch",
                            "code", f.state(Branch.class, branch, Branch::getCode)));
                }).onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("DELETE /tenant/branches/{id}", BRANCH, (f, s, p) -> {
                    Long branch = f.freshBranch(s, true);
                    p.watch(() -> f.state(Branch.class, branch, b -> String.valueOf(b.getIsActive())));
                    return delete("/tenant/branches/{id}", branch);
                }).onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("PUT /tenant/branches/{id}/reactivate", BRANCH, (f, s, p) -> {
                    Long branch = f.freshBranch(s, false);
                    p.watch(() -> f.state(Branch.class, branch, b -> String.valueOf(b.getIsActive())));
                    return put("/tenant/branches/{id}/reactivate", branch);
                }).onlyFor(MANAGERS).deniedFor(AGENTS),

                // ---- trusted IPs -------------------------------------------------------------------
                EndpointSpec.of("GET /tenant/branches/{branchId}/trusted-ips", BRANCH,
                        (f, s, p) -> get("/tenant/branches/{branchId}/trusted-ips", f.branch(s)))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("POST /tenant/branches/trusted-ips", BRANCH, (f, s, p) -> withJson(post("/tenant/branches/trusted-ips"),
                        json("branchId", f.branch(s), "ipAddress", "203.0.113." + (unique(f) % 250 + 1), "label", "IT")))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("DELETE /tenant/branches/trusted-ips/{id}", BRANCH, (f, s, p) -> {
                    Long ip = f.freshTrustedIp(s);
                    p.watch(() -> String.valueOf(f.exists(BranchTrustedIp.class, ip)));
                    return delete("/tenant/branches/trusted-ips/{id}", ip);
                }).onlyFor(MANAGERS).deniedFor(AGENTS),

                // ---- roles -------------------------------------------------------------------------
                EndpointSpec.of("GET /tenant/roles", ORG,
                        (f, s, p) -> get("/tenant/roles").param("organizationId", str(f.org(s.org()))))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("POST /tenant/roles", ORG, (f, s, p) -> withJson(post("/tenant/roles"), json(
                        "organizationId", f.org(s.org()), "roleName", "IT New Role " + unique(f), "roleLevel", "BRANCH",
                        "permissions", LEADS_VIEW)))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("PUT /tenant/roles/{id}", ORG, (f, s, p) -> {
                    Long role = f.freshRole(s);
                    p.watch(() -> f.state(Role.class, role, r -> r.getRoleName() + "|" + r.getDataScope() + "|" + r.getRoleLevel()));
                    return withJson(put("/tenant/roles/{id}", role), json("roleName", "IT Renamed Role", "roleLevel", "BRANCH",
                            "permissions", LEADS_VIEW));
                }).onlyFor(MANAGERS).deniedFor(AGENTS),

                // ---- users -------------------------------------------------------------------------
                EndpointSpec.of("GET /tenant/users", COMPANY,
                        (f, s, p) -> get("/tenant/users").param("companyId", str(f.company(s)))),
                EndpointSpec.of("POST /tenant/users", BRANCH, (f, s, p) -> withJson(post("/tenant/users"), json(
                        "organizationId", f.org(s.org()), "roleId", f.role(s.org(), Persona.AGENT_OWN_1), "email", f.freshEmail(s),
                        "password", "It-Password-123", "firstName", "IT New", "lastName", "User",
                        "allowedCompanyIds", List.of(f.company(s)), "allowedBranchIds", List.of(f.branch(s)))))
                        .onlyFor(MANAGERS).deniedFor(AGENTS),
                EndpointSpec.of("PUT /tenant/users/{id}", BRANCH, (f, s, p) -> {
                    Long user = f.freshUser(s);
                    p.watch(() -> f.state(User.class, user, u -> u.getFirstName() + "|" + u.getRole().getId() + "|" + u.getIsActive()));
                    return withJson(put("/tenant/users/{id}", user), json("roleId", f.role(s.org(), Persona.AGENT_OWN_1), "firstName", "IT Renamed",
                            "email", f.state(User.class, user, User::getEmail),
                            "allowedCompanyIds", List.of(f.company(s)), "allowedBranchIds", List.of(f.branch(s))));
                }).onlyFor(MANAGERS).deniedFor(AGENTS));
    }

    /** A number no other call in this run has used (codes, role names, IPs). */
    private static int unique(com.leads.it.fixture.TenantFixture f) {
        return Integer.parseInt(f.phone().substring(6));
    }

    private TenantRows() { }
}
