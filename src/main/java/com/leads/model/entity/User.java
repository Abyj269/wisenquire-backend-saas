package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Column(nullable = false)
    private boolean gettingStartedSeen;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private Long sessionVersion = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    // Email is the sole login/security identity — see CustomUserDetailsService,
    // JwtTokenProvider (JWT subject), and AuthService. No separate username.
    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false, length = 255)
    @JsonIgnore
    private String password;

    @Column(nullable = false, length = 80)
    private String firstName;

    @Column(length = 80)
    private String lastName;

    @Column(length = 50)
    private String phone;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isSuperAdmin = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    // A trusted user can log in from any IP (no branch allowlist check) but
    // must complete a 4-digit email OTP challenge; everyone else is only
    // allowed to log in from an IP configured as trusted for one of their
    // branches (see BranchTrustedIp) — enforced in AuthService.login().
    @Builder.Default
    @Column(nullable = false)
    private Boolean isTrustedUser = false;

    // Plain @ManyToMany Sets get rehydrated by Hibernate as a PersistentSet
    // backed by a HashSet on every fresh query, so the LinkedHashSet default
    // above only helps a transient (not-yet-reloaded) instance — @OrderBy
    // is what actually makes "the first allocated company/branch" (used to
    // auto-select on login) deterministic once the entity comes back from
    // the database.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_company_mappings",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "company_id")
    )
    @OrderBy("id ASC")
    @Builder.Default
    private Set<Company> allowedCompanies = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_branch_mappings",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "branch_id")
    )
    @OrderBy("id ASC")
    @Builder.Default
    private Set<Branch> allowedBranches = new LinkedHashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public String getFullName() {
        return (firstName + (lastName != null ? " " + lastName : "")).trim();
    }
}
