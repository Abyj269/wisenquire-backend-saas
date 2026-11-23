package com.leads.dto.whatsapp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartConversationRequest {
    @NotNull(message = "WhatsApp number is required")
    private Long configId;

    @NotBlank(message = "Customer name is required")
    private String customerName;

    @NotBlank(message = "Phone number is required")
    private String phone;
}
