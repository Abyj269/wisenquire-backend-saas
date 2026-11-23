package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One allowed login IP address for a branch. A branch can have several of
 * these — a non-trusted user assigned to that branch may only log in from
 * one of them; a trusted user skips this check entirely in favor of email OTP.
 */
@Entity
@Table(name = "branch_trusted_ips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchTrustedIp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    @JsonIgnore
    private Branch branch;

    @Column(nullable = false, length = 64)
    private String ipAddress;

    @Column(length = 150)
    private String label;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
