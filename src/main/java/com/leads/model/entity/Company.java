package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "companies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(length = 50)
    private String taxNumber;

    @Builder.Default
    @Column(length = 10)
    private String currency = "USD";

    // LONGTEXT rather than a short varchar (or plain TEXT, whose 64KB cap a
    // base64-encoded image easily exceeds) since an uploaded logo is stored
    // here as a base64 data URI, not just a short external link.
    @Column(columnDefinition = "LONGTEXT")
    private String logoUrl;

    // Display-only: every LocalDateTime column still stores the server's own
    // wall-clock (Asia/Dubai) — this is what created/updated timestamps are
    // converted to when shown, and what "now" means for this company's
    // branch business hours/holidays.
    @Builder.Default
    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Dubai";

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @OneToMany(mappedBy = "company", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Branch> branches = new ArrayList<>();

    @OneToOne(mappedBy = "company", cascade = CascadeType.ALL, orphanRemoval = true)
    private CompanyLeadSettings leadSettings;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Module 06 P1 (V70): a random, unique 6-character code printed in document numbers instead of
     * the internal id (flag documents.company_code_prefix). Generated once; never derived from the id.
     */
    @Column(unique = true, length = 12)
    private String documentCode;

    @PrePersist
    void defaultDocumentCode() {
        if (documentCode == null || documentCode.isBlank()) documentCode = com.leads.service.DocumentNumberService.randomCompanyCode();
    }
}
