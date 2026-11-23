package com.leads.service;

import com.leads.model.entity.*;
import com.leads.model.enums.RoleLevel;
import com.leads.repository.LeadRepository;
import com.leads.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantAccessTest {
    EntityManager em = mock(EntityManager.class);
    UserRepository users = mock(UserRepository.class);
    LeadRepository leads = mock(LeadRepository.class);
    TenantAccess access = new TenantAccess(em, users, leads);
    Organization org = Organization.builder().id(1L).isActive(true).build();
    Company allowed = Company.builder().id(10L).organization(org).isActive(true).build();
    Company other = Company.builder().id(20L).organization(org).isActive(true).build();

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    void signIn(RoleLevel level) {
        var user = User.builder().email("agent@example.test").isActive(true).organization(org)
                .role(Role.builder().roleLevel(level).build()).allowedCompanies(new HashSet<>(Set.of(allowed))).build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(), "", List.of()));
        when(em.find(Company.class, 10L)).thenReturn(allowed);
        when(em.find(Company.class, 20L)).thenReturn(other);
    }
    @Test void companyUserCannotReadAnotherCompany() {
        signIn(RoleLevel.COMPANY);
        assertSame(allowed, access.company(10L));
        assertThrows(AccessDeniedException.class, () -> access.company(20L));
    }
    @Test void branchRoleCannotTreatCompanyMembershipAsAllBranchAccess() {
        signIn(RoleLevel.BRANCH);
        var branch = Branch.builder().id(5L).company(allowed).isActive(true).build();
        when(em.find(Branch.class, 5L)).thenReturn(branch);
        assertThrows(AccessDeniedException.class, () -> access.branch(5L));
        assertThrows(AccessDeniedException.class, () -> access.companyWide(10L));
    }
    @Test void backgroundScopeCannotEscapeAndIsAlwaysCleared() {
        when(em.find(Company.class, 10L)).thenReturn(allowed);
        when(em.find(Company.class, 20L)).thenReturn(other);
        TenantAccess.inCompany(10L, () -> {
            assertSame(allowed, access.company(10L));
            assertThrows(AccessDeniedException.class, () -> access.company(20L));
        });
        assertThrows(AccessDeniedException.class, () -> access.company(10L));
    }
}
