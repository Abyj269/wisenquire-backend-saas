package com.leads.it;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.it.matrix.EndpointCatalog;
import com.leads.model.entity.*;
import com.leads.model.enums.RoleLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Harness self-tests (01 §7): the fixture is what D3 says it is, and discovery sees every endpoint. */
class PersonaFixtureIT extends AbstractIntegrationTest {

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired jakarta.persistence.EntityManager em;

    @Test
    void twoOrgsTwoCompaniesTwoBranchesWithDistinctIds() {
        Set<Long> branches = new HashSet<>(), companies = new HashSet<>();
        for (Slot s : Slot.values()) { branches.add(fixture.branch(s)); companies.add(fixture.company(s)); }
        assertEquals(8, branches.size());
        assertEquals(4, companies.size());
        assertNotEquals(fixture.org('A'), fixture.org('B'));
        fixture.inTx(() -> {
            for (Slot s : Slot.values()) {
                Lead lead = em.find(Lead.class, fixture.lead(s));
                assertEquals(fixture.branch(s), lead.getBranch().getId(), "lead " + s);
                assertEquals(fixture.company(s), lead.getCompany().getId(), "lead company " + s);
                assertEquals(fixture.org(s.org()), lead.getOrganization().getId(), "lead org " + s);
                assertEquals(fixture.branch(s), em.find(WhatsAppConversation.class, fixture.conversation(s)).getBranch().getId());
                assertEquals(fixture.branch(s), em.find(Broadcast.class, fixture.broadcast(s)).getBranch().getId());
                assertNull(em.find(Broadcast.class, fixture.companyWideBroadcast(s)).getBranch(), "company-wide broadcast " + s);
            }
            return null;
        });
    }

    @Test
    void personasHaveTheD3Scopes() {
        fixture.inTx(() -> {
            for (char o : new char[]{'A', 'B'}) {
                Slot home = com.leads.it.fixture.TenantFixture.home(o);
                for (Persona p : Persona.values()) {
                    User u = em.find(User.class, fixture.user(o, p));
                    assertEquals(fixture.org(o), u.getOrganization().getId(), p + " org");
                    assertEquals(p.level, u.getRole().getRoleLevel(), p + " level");
                    assertFalse(Boolean.TRUE.equals(u.getIsSuperAdmin()), p + " must not be super admin");
                    assertNotNull(fixture.token(o, p), p + " token");
                    switch (p) {
                        case ORG_ADMIN -> assertTrue(u.getAllowedCompanies().isEmpty() && u.getAllowedBranches().isEmpty());
                        case COMPANY_MGR -> assertEquals(Set.of(fixture.company(home)), ids(u.getAllowedCompanies()));
                        default -> assertEquals(Set.of(fixture.branch(home)), ids(u.getAllowedBranches()));
                    }
                }
            }
            assertEquals(RoleLevel.ORGANIZATION, Persona.ORG_ADMIN.level);
            return null;
        });
    }

    @Test
    void discoveryFindsEveryApplicationEndpoint() {
        // 01 §7 / plan D3: 188 mappings at v3.1. When an endpoint is added or removed, update
        // this number together with the matrix rows or the pending list.
        assertEquals(188, EndpointCatalog.discover(mappings).size());
    }


    private static Set<Long> ids(Set<?> entities) {
        Set<Long> out = new HashSet<>();
        for (Object e : entities) out.add(e instanceof Company c ? c.getId() : ((Branch) e).getId());
        return out;
    }
}
