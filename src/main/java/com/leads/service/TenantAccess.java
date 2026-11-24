package com.leads.service;

import com.leads.model.entity.*;
import com.leads.model.enums.RoleLevel;
import com.leads.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Server-owned scope. Never infer authorization from a caller's selected company. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantAccess {
    private final EntityManager em;
    private final UserRepository users;
    private final com.leads.repository.LeadRepository leads;
    private final TenantStatusPolicy statusPolicy;
    /** 01 Part B item 1 kill switch: false = never read the memo (every actor() queries again). */
    @org.springframework.beans.factory.annotation.Value("${app.tenant.actor-memo.enabled:true}")
    private boolean memoEnabled = true;
    private static final ThreadLocal<Long> SYSTEM_COMPANY = new ThreadLocal<>();

    // Only verified webhook/job code in this package may enter a bounded system scope.
    static void inCompany(Long companyId, Runnable work) {
        Long previous = SYSTEM_COMPANY.get();
        try { SYSTEM_COMPANY.set(Objects.requireNonNull(companyId)); work.run(); }
        finally { if (previous == null) SYSTEM_COMPANY.remove(); else SYSTEM_COMPANY.set(previous); }
    }

    public User actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) throw forbidden();
        // Read-only here: only the JWT filter / STOMP interceptor fill the memo (and they clear it),
        // so a pooled async thread can never see another request's user.
        User user = memoEnabled ? ActorMemo.get(auth.getName()) : null;
        if (user == null) user = users.findByEmail(auth.getName()).orElseThrow(TenantAccess::denied);
        if (!Boolean.TRUE.equals(user.getIsActive())) throw forbidden();
        return user;
    }
    public boolean isSuper(User user) {
        return Boolean.TRUE.equals(user.getIsSuperAdmin()) || user.getRole().getRoleLevel() == RoleLevel.SUPER_ADMIN;
    }
    public void superAdmin() { if (!isSuper(actor())) throw forbidden(); }
    public void permission(String authority) {
        if (SYSTEM_COMPANY.get() != null) return;
        if (isSuper(actor())) return;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(authority))) throw forbidden();
    }
    public Organization organization(Long id) {
        Organization org = find(Organization.class, id);
        User user = actor();
        if (!isSuper(user) && (user.getOrganization() == null || !id.equals(user.getOrganization().getId()))) throw denied();
        return org;
    }
    public void organizationAdmin(Long id) {
        organization(id);
        User user = actor();
        if (!isSuper(user) && user.getRole().getRoleLevel() != RoleLevel.ORGANIZATION) throw forbidden();
    }
    public Company company(Long id) {
        Company company = find(Company.class, id);
        if (SYSTEM_COMPANY.get() != null) {
            if (!id.equals(SYSTEM_COMPANY.get())) throw denied();
        } else {
            User user = actor();
            if (!canCompany(user, company)) throw denied();
        }
        if (!Boolean.TRUE.equals(company.getIsActive()) || !Boolean.TRUE.equals(company.getOrganization().getIsActive())) throw denied();
        // D12 tenant state: writes blocked for SUSPENDED/CANCELLED, PAST_DUE past grace, expired TRIAL.
        // Jobs/webhooks (system scope) and the platform super admin are not blocked here; jobs call
        // TenantStatusPolicy.enforce(org, write) themselves.
        if (SYSTEM_COMPANY.get() == null && !isSuper(actor())) statusPolicy.enforce(company.getOrganization());
        return company;
    }
    public boolean canCompany(User user, Company company) {
        if (isSuper(user)) return true;
        if (user.getOrganization() == null || !user.getOrganization().getId().equals(company.getOrganization().getId())) return false;
        if (user.getRole().getRoleLevel() == RoleLevel.ORGANIZATION) return true;
        return user.getAllowedCompanies().stream().anyMatch(c -> c.getId().equals(company.getId()))
            || user.getAllowedBranches().stream().anyMatch(b -> b.getCompany().getId().equals(company.getId()));
    }
    public boolean canBranch(Branch branch) {
        if (SYSTEM_COMPANY.get() != null) return branch.getCompany().getId().equals(SYSTEM_COMPANY.get());
        User user = actor();
        return canCompany(user, branch.getCompany()) && (isSuper(user) || user.getRole().getRoleLevel() != RoleLevel.BRANCH
            || user.getAllowedBranches().stream().anyMatch(b -> b.getId().equals(branch.getId())));
    }
    public Branch branch(Long id) {
        Branch branch = find(Branch.class, id);
        company(branch.getCompany().getId());
        if (!canBranch(branch) || !Boolean.TRUE.equals(branch.getIsActive())) throw denied();
        return branch;
    }
    public void scope(Long companyId, Long branchId) {
        company(companyId);
        if (branchId != null && !branch(branchId).getCompany().getId().equals(companyId)) throw denied();
    }
    public List<Long> branchIds() {
        if (SYSTEM_COMPANY.get() != null) return null;
        User user = actor();
        if (isSuper(user) || user.getRole().getRoleLevel() != RoleLevel.BRANCH) return null;
        return user.getAllowedBranches().stream().filter(b -> Boolean.TRUE.equals(b.getIsActive())).map(Branch::getId).toList();
    }
    public void companyWide(Long id) {
        company(id);
        if (branchIds() != null) throw new AccessDeniedException("Select an authorized branch for this report.");
    }
    public void sameCompany(Long expected, Company actual) {
        if (actual == null || !Objects.equals(expected, actual.getId())) throw new IllegalArgumentException("Linked record belongs to another company.");
    }
    public <T> T find(Class<T> type, Long id) {
        if (id == null) throw new IllegalArgumentException("A record/company ID is required.");
        T value = em.find(type, id);
        if (value == null) throw new com.leads.exception.RecordNotFoundException("Record not found.");
        return value;
    }
    public <T> T record(Class<T> type, Long id) {
        T value = find(type, id);
        if (value instanceof Lead x) { company(x.getCompany().getId()); sameCompany(x.getCompany().getId(), branch(x.getBranch().getId()).getCompany()); }
        else if (value instanceof Customer x) {
            company(x.getCompany().getId());
            List<Long> permitted = branchIds();
            if (permitted != null && !leads.existsByCustomerIdAndBranchIdIn(x.getId(), permitted)) throw denied();
        }
        else if (value instanceof Quotation x) { company(x.getCompany().getId()); sameCompany(x.getCompany().getId(), record(Lead.class, x.getLead().getId()).getCompany()); }
        else if (value instanceof WhatsAppMessage x) record(WhatsAppConversation.class, x.getConversation().getId());
        else if (value instanceof WhatsAppConversation x) { company(x.getCompany().getId()); branch(x.getBranch().getId()); }
        else if (value instanceof WhatsAppConfiguration x) { company(x.getCompany().getId()); branch(x.getBranch().getId()); }
        // F-1: a branch broadcast needs that branch; a company-wide one (no branch) needs company-wide scope.
        else if (value instanceof Broadcast x) { company(x.getCompany().getId()); if (x.getBranch() != null) branch(x.getBranch().getId()); else companyWide(x.getCompany().getId()); }
        else if (value instanceof AiProviderConfig x) { company(x.getCompany().getId()); branch(x.getBranch().getId()); }
        else if (value instanceof AiBranchSettings x) branch(x.getBranch().getId());
        else if (value instanceof LeadConversionScore x) company(x.getCompany().getId());
        else if (value instanceof WhatsAppMessageTranslation x) record(WhatsAppMessage.class, x.getMessage().getId());
        else if (value instanceof WhatsAppConversationSummary x) record(WhatsAppConversation.class, x.getConversation().getId());
        else if (value instanceof WhatsAppMessageTranscription x) record(WhatsAppMessage.class, x.getMessage().getId());
        else if (value instanceof AdAccountConnection x) { company(x.getCompany().getId()); if (x.getBranch()!=null) branch(x.getBranch().getId()); else companyWide(x.getCompany().getId()); }
        else if (value instanceof WhatsAppHoliday x) { company(x.getCompany().getId()); x.getBranches().forEach(b -> branch(b.getId())); }
        else if (value instanceof BranchTrustedIp x) branch(x.getBranch().getId());
        else if (value instanceof LeadStatus x) company(x.getCompany().getId());
        else if (value instanceof LeadSource x) company(x.getCompany().getId());
        else if (value instanceof LeadPurpose x) company(x.getCompany().getId());
        else if (value instanceof LeadLostReason x) company(x.getCompany().getId());
        else if (value instanceof ProductMaster x) company(x.getCompany().getId());
        else if (value instanceof DynamicFieldDefinition x) company(x.getCompany().getId());
        else if (value instanceof Delivery x) { company(x.getCompany().getId()); sameCompany(x.getCompany().getId(), record(Lead.class, x.getLead().getId()).getCompany()); }
        else if (value instanceof DeliveryFailureReason x) company(x.getCompany().getId());
        else throw new IllegalArgumentException("Unsupported scoped record type.");
        return value;
    }
    /** A permission or role refusal (not a hidden record): always 403 FORBIDDEN, whatever the flag (D12). */
    private static AccessDeniedException forbidden() { return new AccessDeniedException("You don't have permission to do this."); }

    /** Out of scope: 403 today, 404 NOT_FOUND with flag errors.uniform_not_found (D12 / F-9). */
    private static AccessDeniedException denied() { return new com.leads.exception.ScopeDeniedException("This record is outside your permitted scope."); }
}
