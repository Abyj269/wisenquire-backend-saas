package com.leads.it;

import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * D14 primitive (01 Part B item 8): TenantAccess.record() applies the OWN rules for leads,
 * conversations, customers, quotations and deliveries, but only for OWN roles in an org with
 * own_scope.enforced ON. The fixture's AGENT_OWN_1/2 have an OWN role; branch managers are LEVEL.
 * (List filtering, assignment rules and STOMP topics are module work: 06-12.)
 */
class OwnScopePrimitiveIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;

    private Long a1, a2, mgr;

    @BeforeEach
    void setUp() {
        a1 = fixture.user('A', Persona.AGENT_OWN_1);
        a2 = fixture.user('A', Persona.AGENT_OWN_2);
        mgr = fixture.user('A', Persona.BRANCH_MGR);
        enforce(true);
    }

    @AfterEach
    void tearDown() {
        flags.clearOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'));
        flags.clearOverride(FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, fixture.org('A'));
    }

    private void enforce(boolean on) {
        flags.setOverride(FeatureFlag.OWN_SCOPE_ENFORCED, fixture.org('A'), on, "it", "OwnScopePrimitiveIT");
    }

    private int status(Persona p, String path, Object id) throws Exception {
        return mvc.perform(get(path, id).header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse().getStatus();
    }

    @Test
    void leadsAreVisibleOnlyToTheirAssignee() throws Exception {
        Long mine = fixture.freshLeadAssignedTo(Slot.A1a, a1);
        assertEquals(200, status(Persona.AGENT_OWN_1, "/leads/{id}", mine));
        assertEquals(403, status(Persona.AGENT_OWN_2, "/leads/{id}", mine), "another agent's lead (OWN-RECORD)");
        assertEquals(200, status(Persona.BRANCH_MGR, "/leads/{id}", mine), "LEVEL roles are unchanged");
    }

    @Test
    void unassignedLeadsAreHiddenUnlessTheOrgShowsThem() throws Exception {
        Long unassigned = fixture.freshLeadAssignedTo(Slot.A1a, null);
        assertEquals(403, status(Persona.AGENT_OWN_1, "/leads/{id}", unassigned));
        flags.setOverride(FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, fixture.org('A'), true, "it", "test");
        assertEquals(200, status(Persona.AGENT_OWN_1, "/leads/{id}", unassigned));
    }

    @Test
    void withTheFlagOffOwnRolesBehaveLikeBranchRoles() throws Exception {
        enforce(false);
        Long notMine = fixture.freshLeadAssignedTo(Slot.A1a, a2);
        assertEquals(200, status(Persona.AGENT_OWN_1, "/leads/{id}", notMine));
    }

    @Test
    void conversationsByAgentOrByTheirLeadsAssignee() throws Exception {
        Long mineAsAgent = fixture.freshConversationFor(Slot.A1a, a1, null);
        Long viaMyLead = fixture.freshConversationFor(Slot.A1a, null, fixture.freshLeadAssignedTo(Slot.A1a, a1));
        Long someoneElses = fixture.freshConversationFor(Slot.A1a, a2, null);
        assertEquals(200, status(Persona.AGENT_OWN_1, "/whatsapp/conversations/{id}", mineAsAgent));
        assertEquals(200, status(Persona.AGENT_OWN_1, "/whatsapp/conversations/{id}", viaMyLead));
        assertEquals(403, status(Persona.AGENT_OWN_1, "/whatsapp/conversations/{id}", someoneElses));
        assertEquals(403, status(Persona.AGENT_OWN_1, "/whatsapp/conversations/{id}/messages", someoneElses), "messages follow their conversation");
    }

    @Test
    void customersThroughAVisibleLeadOrConversation() throws Exception {
        Long myLead = fixture.freshLeadAssignedTo(Slot.A1a, a1);
        Long customer = fixture.customerOfLead(myLead);
        assertEquals(200, status(Persona.AGENT_OWN_1, "/customers/{id}", customer));
        assertEquals(403, status(Persona.AGENT_OWN_2, "/customers/{id}", customer));
        Long otherLead = fixture.freshLeadAssignedTo(Slot.A1a, a2);
        fixture.freshConversationFor(Slot.A1a, a1, otherLead); // a1 is the agent on a chat about this customer
        // Module 07 P1: the chat half is behind own_scope.customer_via_chats (default OFF until module 12).
        assertEquals(403, status(Persona.AGENT_OWN_1, "/customers/{id}", fixture.customerOfLead(otherLead)), "chat half OFF");
        flags.setOverride(FeatureFlag.OWN_SCOPE_CUSTOMER_VIA_CHATS, fixture.org('A'), true, "it", "OwnScopePrimitiveIT");
        try {
            assertEquals(200, status(Persona.AGENT_OWN_1, "/customers/{id}", fixture.customerOfLead(otherLead)), "chat half ON");
        } finally {
            flags.clearOverride(FeatureFlag.OWN_SCOPE_CUSTOMER_VIA_CHATS, fixture.org('A'));
        }
    }

    @Test
    void quotationsByCreatorOrByTheirLead() throws Exception {
        Long othersLead = fixture.freshLeadAssignedTo(Slot.A1a, a2);
        Long createdByMe = fixture.freshQuotation(Slot.A1a, othersLead, a1);
        Long onMyLead = fixture.freshQuotation(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a1), mgr);
        Long neither = fixture.freshQuotation(Slot.A1a, othersLead, mgr);
        assertEquals(200, status(Persona.AGENT_OWN_1, "/quotations/{id}", createdByMe), "creator, even on someone else's lead");
        assertEquals(200, status(Persona.AGENT_OWN_1, "/quotations/{id}", onMyLead));
        assertEquals(403, status(Persona.AGENT_OWN_1, "/quotations/{id}", neither));
    }

    @Test
    void deliveriesByAssigneeOrByTheirLead() throws Exception {
        Long othersLead = fixture.freshLeadAssignedTo(Slot.A1a, a2);
        assertEquals(200, status(Persona.AGENT_OWN_1, "/deliveries/{id}", fixture.freshDelivery(Slot.A1a, othersLead, a1)));
        assertEquals(200, status(Persona.AGENT_OWN_1, "/deliveries/{id}", fixture.freshDelivery(Slot.A1a, fixture.freshLeadAssignedTo(Slot.A1a, a1), null)));
        assertEquals(403, status(Persona.AGENT_OWN_1, "/deliveries/{id}", fixture.freshDelivery(Slot.A1a, othersLead, mgr)));
    }

    @Test
    void theProfileAndTokenSayOwn() throws Exception {
        String body = mvc.perform(get("/auth/profile").header("Authorization", "Bearer " + fixture.token('A', Persona.AGENT_OWN_1)))
                .andReturn().getResponse().getContentAsString();
        assertEquals("OWN", new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("dataScope").asText(), body);
    }
}
