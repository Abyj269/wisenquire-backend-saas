package com.leads.dto.ai;

import com.leads.model.entity.AiBranchSettings;
import com.leads.model.entity.WhatsAppConversation;
import lombok.*;

import java.util.List;

/** Output of AiContextAssemblyService — a conversation's chat history, already tenant-checked, bounded, and mapped to the provider-agnostic message shape. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssembledAiContext {
    private WhatsAppConversation conversation;
    private AiBranchSettings settings;
    private List<AiChatMessage> messages;
    private int messageCount;
}
