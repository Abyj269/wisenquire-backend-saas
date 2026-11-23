package com.leads.dto.broadcast;

import lombok.*;

/**
 * Represents a single parameter passed to a WhatsApp template component.
 *
 * For broadcast sends, parameters are NOT pre-filled at campaign creation time
 * (since each recipient's value will differ). Instead, each parameter carries a
 * "binding" that describes WHERE to get the value at dispatch time:
 *
 *   binding = "recipientName"   → resolved from BroadcastRecipient.recipientName
 *   binding = "recipientPhone"  → resolved from BroadcastRecipient.recipientPhone
 *   binding = "static"          → use the literal text in the `text` field
 *
 * If binding is absent (legacy/outbound send), `text` is used directly.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastParameterDto {
    /** Always "text" for broadcast template params (Meta API type). */
    private String type;

    /**
     * The resolved literal value for this parameter.
     * - For binding="static": set by the user at campaign creation time.
     * - For binding="recipientName/recipientPhone": resolved at dispatch time per recipient.
     */
    private String text;

    /**
     * Variable binding key. Controls how `text` is resolved at send time:
     * "recipientName" | "recipientPhone" | "static" (use text as-is) | null (legacy, use text)
     */
    private String binding;
}
