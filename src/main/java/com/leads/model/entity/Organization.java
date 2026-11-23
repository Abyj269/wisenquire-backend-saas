package com.leads.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(length = 50)
    private String subscriptionPlan;

    // LONGTEXT, not TEXT — TEXT's 64KB cap a base64-encoded logo easily exceeds.
    @Column(columnDefinition = "LONGTEXT")
    private String logoUrl;

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Company> companies = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
