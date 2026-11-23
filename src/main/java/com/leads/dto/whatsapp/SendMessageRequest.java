package com.leads.dto.whatsapp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendMessageRequest {
    @NotNull(message = "Conversation ID is required")
    private Long conversationId;

    @NotBlank(message = "Message text is required")
    private String message;

    private String messageType; // TEXT, TEMPLATE, IMAGE, DOCUMENT
    private String mediaUrl;
    private String templateName;
    private String templateLanguage;
    private String expectedTemplateCategory;
    /** Values for the template body's {{1}}, {{2}}, ... placeholders, in order — omit/empty if the template has none. */
    private java.util.List<String> templateBodyParams;

    // Persisted client-generated idempotency/correlation key echoed back on the DTO
    // returned here and on the WebSocket broadcast, so the sending browser
    // tab can reconcile its optimistic bubble with whichever of those two
    // arrives first.
    private String clientMessageId;
}
