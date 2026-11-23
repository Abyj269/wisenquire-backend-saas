package com.leads.dto.ai;

import com.leads.model.enums.AiChatRole;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiChatMessage {
    private AiChatRole role;
    private String content;
}
