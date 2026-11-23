package com.leads.dto.broadcast;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BroadcastComponentDto {
    private String type;
    private String format;
    private String text;
    private List<BroadcastParameterDto> parameters;
}
