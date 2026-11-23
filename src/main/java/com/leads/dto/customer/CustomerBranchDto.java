package com.leads.dto.customer;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerBranchDto {
    private Long id;
    private String name;
    private String code;
}
