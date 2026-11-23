package com.leads.dto.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhatsAppTemplateDto {
    private String id;
    private String name;
    private String status;
    private String category;
    private String correctCategory;
    private String rejectedReason;
    private String parameterFormat;
    private JsonNode latestCategoryEvent;
    private String language;
    private String qualityScore;
    private JsonNode components;
}
