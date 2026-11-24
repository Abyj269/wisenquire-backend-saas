package com.leads.repository;

import com.leads.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("UPDATE User u SET u.sessionVersion = u.sessionVersion + 1 WHERE u.role.id = :roleId")
    int revokeSessionsForRole(@Param("roleId") Long roleId);
    Optional<User> findByEmail(String email);

    /**
     * 01 Part B item 1: everything TenantAccess needs, in one query, so the user can be used
     * detached for the rest of the request (ActorMemo). Only role.permissions is a list.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "role", "role.permissions", "organization", "allowedCompanies", "allowedBranches", "allowedBranches.company"})
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findForAuthenticationByEmail(@Param("email") String email);
    boolean existsByEmail(String email);

    List<User> findByOrganizationId(Long organizationId);

    @Query("SELECT u FROM User u JOIN u.allowedCompanies c WHERE c.id = :companyId")
    List<User> findByAllowedCompanyId(@Param("companyId") Long companyId);

    @Query("SELECT u FROM User u JOIN u.allowedBranches b WHERE b.id = :branchId")
    List<User> findByAllowedBranchId(@Param("branchId") Long branchId);
}
