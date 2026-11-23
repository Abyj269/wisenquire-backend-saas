package com.leads.dto.whatsapp;

import lombok.*;

import java.util.List;

/** One vCard shared in a WhatsApp "contacts" message — a single message can carry several of these. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppContactDto {
    private String name;
    private List<String> phones;
}
