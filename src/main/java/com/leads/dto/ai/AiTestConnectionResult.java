package com.leads.dto.ai;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiTestConnectionResult {
    private boolean success;
    private String message;
}
