package com.leads.dto.ads;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdConnectionTestResult {
    private boolean success;
    private String message;
    private String accountDisplayName;
    private String accountCurrency;
}
