package com.leads.repository;

import com.leads.model.entity.Role;
import com.leads.model.enums.RoleLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    List<Role> findByOrganizationId(Long organizationId);
    List<Role> findByRoleLevel(RoleLevel roleLevel);
    Optional<Role> findByOrganizationIdAndRoleName(Long organizationId, String roleName);
    Optional<Role> findByRoleNameAndIsSystemRoleTrue(String roleName);
}
