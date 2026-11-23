package com.leads.dto.customer;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerUpdateRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Mobile number is required")
    private String mobileNumber;

    private String whatsappNumber;
    private String email;
    private String customerCompanyName;
    private String city;
    private String notes;
}
