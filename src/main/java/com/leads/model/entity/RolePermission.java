package com.leads.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "role_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    @JsonIgnore
    private Role role;

    @Column(nullable = false, length = 80)
    private String moduleName;

    @Builder.Default
    @Column(nullable = false)
    private Boolean canView = true;

    @Builder.Default
    @Column(nullable = false)
    private Boolean canAdd = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean canEdit = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean canDelete = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean canExport = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean canApprove = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean canAssign = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean canPrint = false;
}
