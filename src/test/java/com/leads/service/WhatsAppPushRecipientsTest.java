package com.leads.service;

import com.leads.model.entity.Lead;
import com.leads.model.entity.User;
import com.leads.model.entity.WhatsAppConversation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Module 12 P1 (D14 OWN-PUSH): who gets a conversation update on their own STOMP queue. */
class WhatsAppPushRecipientsTest {

    private static User user(String email) { return User.builder().email(email).build(); }

    @Test
    void theAgentAndTheLeadAssigneeOnly() {
        WhatsAppConversation c = WhatsAppConversation.builder().assignedAgent(user("a2@x")).build();
        assertEquals(Set.of("a2@x"), WhatsAppCloudService.pushRecipients(c), "agent 2's chat reaches agent 2 only");
        c.setLead(Lead.builder().assignedTo(user("a1@x")).build());
        assertEquals(Set.of("a2@x", "a1@x"), WhatsAppCloudService.pushRecipients(c));
        c.setLead(Lead.builder().assignedTo(user("a2@x")).build());
        assertEquals(Set.of("a2@x"), WhatsAppCloudService.pushRecipients(c), "no duplicates");
    }

    @Test
    void nobodyForAnUnassignedChat() {
        assertEquals(Set.of(), WhatsAppCloudService.pushRecipients(WhatsAppConversation.builder().build()), "managers get it on the branch topic");
    }
}
