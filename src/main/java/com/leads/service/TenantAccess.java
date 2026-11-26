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
    private final com.leads.flags.FeatureFlags flags;
    /** 01 Part B item 1 kill switch: false = never read the memo (every actor() queries again). */
    @org.springframework.beans.factory.annotation.Value("${app.tenant.actor-memo.enabled:true}")
    private boolean memoEnabled = true;
    private static final ThreadLocal<Long> SYSTEM_COMPANY = new ThreadLocal<>();

    // Only verified webhook/job code in this package may enter a bounded system scope.
    /**
     * 01 Part B item 6: carries the CURRENT system scope (if any) into a task that runs on another
     * thread, and restores that thread's previous value afterwards. It cannot choose a company:
     * only code already inside inCompany() can hand its own scope on.
     */
    public static Runnable propagateSystemScope(Runnable task) {
        Long captured = SYSTEM_COMPANY.get();
        if (captured == null) return task;
        return () -> inCompany(captured, task);
    }

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
        // D12 tenant state also on organization-level writes (companies, roles, users, the org itself),
        // not only through company() (found by the module 03 STATE rows). Super admin and system scope excepted.
        if (SYSTEM_COMPANY.get() == null && !isSuper(user)) statusPolicy.enforce(org);
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
    /**
     * Loads a record and checks it is in the caller's scope (org, company, branch) and, for OWN users
     * with flag own_scope.enforced on, that it is one of their own (D14, 01 Part B item 8).
     */
    public <T> T record(Class<T> type, Long id) { return recordScoped(type, id, true); }

    /** @param own false = organization/company/branch scope only (used for the lead behind a quotation or delivery). */
    private <T> T recordScoped(Class<T> type, Long id, boolean own) {
        T value = find(type, id);
        if (value instanceof Lead x) { company(x.getCompany().getId()); sameCompany(x.getCompany().getId(), branch(x.getBranch().getId()).getCompany()); }
        else if (value instanceof Customer x) {
            company(x.getCompany().getId());
            List<Long> permitted = branchIds();
            if (permitted != null && !leads.existsByCustomerIdAndBranchIdIn(x.getId(), permitted)) throw denied();
        }
        else if (value instanceof Quotation x) { company(x.getCompany().getId()); sameCompany(x.getCompany().getId(), recordScoped(Lead.class, x.getLead().getId(), false).getCompany()); }
        else if (value instanceof WhatsAppMessage x) recordScoped(WhatsAppConversation.class, x.getConversation().getId(), own);
        else if (value instanceof WhatsAppConversation x) { company(x.getCompany().getId()); branch(x.getBranch().getId()); }
        else if (value instanceof WhatsAppConfiguration x) { company(x.getCompany().getId()); branch(x.getBranch().getId()); }
        // F-1: a branch broadcast needs that branch; a company-wide one (no branch) needs company-wide scope.
        else if (value instanceof Broadcast x) { company(x.getCompany().getId()); if (x.getBranch() != null) branch(x.getBranch().getId()); else companyWide(x.getCompany().getId()); }
        else if (value instanceof AiProviderConfig x) { company(x.getCompany().getId()); branch(x.getBranch().getId()); }
        else if (value instanceof AiBranchSettings x) branch(x.getBranch().getId());
        else if (value instanceof LeadConversionScore x) company(x.getCompany().getId());
        else if (value instanceof WhatsAppMessageTranslation x) recordScoped(WhatsAppMessage.class, x.getMessage().getId(), own);
        else if (value instanceof WhatsAppConversationSummary x) recordScoped(WhatsAppConversation.class, x.getConversation().getId(), own);
        else if (value instanceof WhatsAppMessageTranscription x) recordScoped(WhatsAppMessage.class, x.getMessage().getId(), own);
        else if (value instanceof AdAccountConnection x) { company(x.getCompany().getId()); if (x.getBranch()!=null) branch(x.getBranch().getId()); else companyWide(x.getCompany().getId()); }
        else if (value instanceof WhatsAppHoliday x) { company(x.getCompany().getId()); x.getBranches().forEach(b -> branch(b.getId())); }
        else if (value instanceof BranchTrustedIp x) branch(x.getBranch().getId());
        else if (value instanceof LeadStatus x) company(x.getCompany().getId());
        else if (value instanceof LeadSource x) company(x.getCompany().getId());
        else if (value instanceof LeadPurpose x) company(x.getCompany().getId());
        else if (value instanceof LeadLostReason x) company(x.getCompany().getId());
        else if (value instanceof ProductMaster x) company(x.getCompany().getId());
        else if (value instanceof DynamicFieldDefinition x) company(x.getCompany().getId());
        else if (value instanceof Delivery x) { company(x.getCompany().getId()); sameCompany(x.getCompany().getId(), recordScoped(Lead.class, x.getLead().getId(), false).getCompany()); }
        else if (value instanceof DeliveryFailureReason x) company(x.getCompany().getId());
        else throw new IllegalArgumentException("Unsupported scoped record type.");
        if (own) ownHook(value);
        return value;
    }

    // ---- D14 OWN primitive (01 Part B item 8) ------------------------------------------------

    /** The caller's user id when the OWN scope applies to them, else null (LEVEL, flag off, system scope, super admin). */
    public Long ownerFilter() {
        User me = ownUser();
        return me == null ? null : me.getId();
    }

    /** With an active OWN scope: whether a customer is also visible through a chat assigned to me (flag own_scope.customer_via_chats, module 07). */
    public boolean ownCustomerViaChats() {
        User me = ownUser();
        return me != null && flags.isOn(com.leads.flags.FeatureFlag.OWN_SCOPE_CUSTOMER_VIA_CHATS, me.getOrganization().getId());
    }

    /** With an active OWN scope: whether unassigned leads/conversations are also visible (flag own_scope.include_unassigned). */
    public boolean ownIncludesUnassigned() {
        User me = ownUser();
        return me != null && flags.isOn(com.leads.flags.FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, me.getOrganization().getId());
    }

    private User ownUser() {
        if (SYSTEM_COMPANY.get() != null) return null;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) return null;
        User me = actor();
        if (isSuper(me) || me.getRole().getDataScope() != com.leads.model.enums.DataScope.OWN || me.getOrganization() == null) return null;
        return flags.isOn(com.leads.flags.FeatureFlag.OWN_SCOPE_ENFORCED, me.getOrganization().getId()) ? me : null;
    }

    /** D14 visibility rules; runs after the org/company/branch checks have passed. */
    private void ownHook(Object value) {
        if (!(value instanceof Lead || value instanceof WhatsAppConversation || value instanceof Customer
                || value instanceof Quotation || value instanceof Delivery)) return;
        User me = ownUser();
        if (me == null) return;
        boolean unassigned = flags.isOn(com.leads.flags.FeatureFlag.OWN_SCOPE_INCLUDE_UNASSIGNED, me.getOrganization().getId());
        boolean visible;
        if (value instanceof Lead x) visible = ownsLead(x, me, unassigned);
        else if (value instanceof WhatsAppConversation x) visible = ownsConversation(x, me, unassigned);
        else if (value instanceof Customer x) visible = ownsCustomer(x, me, unassigned);
        else if (value instanceof Quotation x) visible = isUser(x.getCreatedBy(), me) || ownsLead(x.getLead(), me, unassigned);
        else visible = isUser(((Delivery) value).getAssignedTo(), me) || ownsLead(((Delivery) value).getLead(), me, unassigned);
        if (!visible) throw denied();
    }

    private static boolean isUser(User u, User me) { return u != null && Objects.equals(u.getId(), me.getId()); }

    private static boolean ownsLead(Lead lead, User me, boolean unassigned) {
        return lead != null && (isUser(lead.getAssignedTo(), me) || (unassigned && lead.getAssignedTo() == null));
    }

    private static boolean ownsConversation(WhatsAppConversation c, User me, boolean unassigned) {
        if (isUser(c.getAssignedAgent(), me)) return true;
        if (c.getLead() != null && isUser(c.getLead().getAssignedTo(), me)) return true;
        return unassigned && c.getAssignedAgent() == null && (c.getLead() == null || c.getLead().getAssignedTo() == null);
    }

    /** A customer has no owner column: visible when one of its leads or conversations is visible (D14). */
    private boolean ownsCustomer(Customer c, User me, boolean unassigned) {
        Long leadsSeen = em.createQuery("SELECT COUNT(l) FROM Lead l LEFT JOIN l.assignedTo a WHERE l.customer.id = :cid"
                        + " AND (a.id = :me OR (:unassigned = true AND a IS NULL))", Long.class)
                .setParameter("cid", c.getId()).setParameter("me", me.getId()).setParameter("unassigned", unassigned).getSingleResult();
        if (leadsSeen > 0) return true;
        // Module 07 P1: the chat half (a chat assigned to me links to this customer) waits for module 12's
        // OWN rules for conversations: flag own_scope.customer_via_chats, default OFF.
        if (!flags.isOn(com.leads.flags.FeatureFlag.OWN_SCOPE_CUSTOMER_VIA_CHATS, me.getOrganization().getId())) return false;
        Long chatsSeen = em.createQuery("SELECT COUNT(c) FROM WhatsAppConversation c JOIN c.lead l LEFT JOIN c.assignedAgent a"
                        + " LEFT JOIN l.assignedTo la WHERE l.customer.id = :cid AND (a.id = :me OR la.id = :me)", Long.class)
                .setParameter("cid", c.getId()).setParameter("me", me.getId()).getSingleResult();
        return chatsSeen > 0;
    }
    /** A permission or role refusal (not a hidden record): always 403 FORBIDDEN, whatever the flag (D12). */
    private static AccessDeniedException forbidden() { return new AccessDeniedException("You don't have permission to do this."); }

    /** Out of scope: 403 today, 404 NOT_FOUND with flag errors.uniform_not_found (D12 / F-9). */
    private static AccessDeniedException denied() { return new com.leads.exception.ScopeDeniedException("This record is outside your permitted scope."); }
}
