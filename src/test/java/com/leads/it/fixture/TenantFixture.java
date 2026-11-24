package com.leads.it.fixture;

import com.leads.config.JwtTokenProvider;
import com.leads.model.entity.*;
import com.leads.model.enums.*;
import com.leads.util.PhoneNumberUtil;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The D3 fixture: 2 orgs x 2 companies x 2 branches, the {@link Persona}s in both orgs, and
 * one of each target record per branch. Built once per JVM (the Spring context and the MySQL
 * container are shared by every IT), so ITs must not delete the shared records; rows that
 * write use the {@code fresh*} factories instead.
 * <p>
 * Everything is stored as IDs: no entity escapes its transaction.
 */
@TestComponent
public class TenantFixture {

    private static final String[] MODULES = {"LEADS", "CUSTOMERS", "BROADCASTS", "WHATSAPP_LIVE_CHAT", "REPORTS_ANALYTICS"};

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final JwtTokenProvider jwt;
    private final AtomicInteger seq = new AtomicInteger(1000);

    private boolean built;
    private final Map<Character, Long> orgId = new HashMap<>();
    private final Map<String, Long> companyId = new HashMap<>();
    private final Map<String, Long> statusId = new HashMap<>();
    private final Map<String, Long> sourceId = new HashMap<>();
    private final Map<String, Long> lostReasonId = new HashMap<>();
    private final Map<String, Long> companyWideBroadcastId = new HashMap<>();
    private final Map<Slot, Long> branchId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> configId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> customerId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> leadId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> conversationId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> messageId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> broadcastId = new EnumMap<>(Slot.class);
    private final Map<String, Long> userId = new HashMap<>();
    private final Map<String, String> token = new HashMap<>();

    public TenantFixture(EntityManager em, PlatformTransactionManager txm, JwtTokenProvider jwt) {
        this.em = em;
        this.tx = new TransactionTemplate(txm);
        this.jwt = jwt;
    }

    // ---- lookups --------------------------------------------------------------------------

    public synchronized TenantFixture ensureBuilt() {
        if (!built) { tx.executeWithoutResult(s -> build()); built = true; }
        return this;
    }

    public Long org(char org) { return ensureBuilt().orgId.get(org); }
    public Long company(Slot s) { return ensureBuilt().companyId.get(s.company()); }
    public Long branch(Slot s) { return ensureBuilt().branchId.get(s); }
    public Long config(Slot s) { return ensureBuilt().configId.get(s); }
    public Long customer(Slot s) { return ensureBuilt().customerId.get(s); }
    public Long lead(Slot s) { return ensureBuilt().leadId.get(s); }
    public Long conversation(Slot s) { return ensureBuilt().conversationId.get(s); }
    public Long message(Slot s) { return ensureBuilt().messageId.get(s); }
    public Long broadcast(Slot s) { return ensureBuilt().broadcastId.get(s); }
    public Long companyWideBroadcast(Slot s) { return ensureBuilt().companyWideBroadcastId.get(s.company()); }
    public Long status(Slot s) { return ensureBuilt().statusId.get(s.company()); }
    public Long source(Slot s) { return ensureBuilt().sourceId.get(s.company()); }
    public Long lostReason(Slot s) { return ensureBuilt().lostReasonId.get(s.company()); }
    public Long user(char org, Persona p) { return ensureBuilt().userId.get(org + ":" + p); }
    public String token(char org, Persona p) { return ensureBuilt().token.get(org + ":" + p); }

    /** The slot a persona "lives" in: its own company x1 and branch x1a. */
    public static Slot home(char org) { return org == 'A' ? Slot.A1a : Slot.B1a; }

    // ---- fresh records for rows that write --------------------------------------------------

    public Long freshLead(Slot s) { return inTx(() -> newLead(s, ensureBuilt().customerId.get(s)).getId()); }
    /** A customer with one lead in {@code s}: branch-level access to a customer goes through its leads. */
    public Long freshCustomer(Slot s) { return inTx(() -> { Customer c = newCustomer(s); newLead(s, c.getId()); return c.getId(); }); }
    public Long freshBroadcast(Slot s) { return inTx(() -> newBroadcast(s, false).getId()); }
    public Long freshCompanyWideBroadcast(Slot s) { return inTx(() -> newBroadcast(s, true).getId()); }
    public Long freshConversation(Slot s) { return inTx(() -> newConversation(s).getId()); }

    /** A snapshot of one record's fields, for "a denied write changed nothing" probes. */
    public <T> String state(Class<T> type, Long id, java.util.function.Function<T, Object> fields) {
        return tx.execute(st -> { T e = em.find(type, id); return e == null ? "<deleted>" : String.valueOf(fields.apply(e)); });
    }

    public boolean exists(Class<?> type, Long id) { return Boolean.TRUE.equals(tx.execute(st -> em.find(type, id) != null)); }

    public <T> T inTx(Supplier<T> work) { ensureBuilt(); return tx.execute(st -> work.get()); }

    /** A unique, valid UAE mobile number. */
    public String phone() { return "+97150" + String.format("%07d", seq.incrementAndGet()); }

    // ---- build ----------------------------------------------------------------------------

    private void build() {
        for (char o : new char[]{'A', 'B'}) {
            Organization org = persist(Organization.builder().name("IT Org " + o).code("IT-ORG-" + o).build());
            orgId.put(o, org.getId());
            Map<Persona, Role> roles = new EnumMap<>(Persona.class);
            for (Persona p : Persona.values()) roles.put(p, role(org, p));

            for (int c = 1; c <= 2; c++) {
                String code = o + "" + c;
                Company company = persist(Company.builder().organization(org).name("IT Company " + code).code("IT-" + code).build());
                companyId.put(code, company.getId());
                statusId.put(code, persist(LeadStatus.builder().company(company).statusName("New").statusCode("NEW")
                        .stageType(StageType.OPEN).isDefault(true).build()).getId());
                persist(LeadStatus.builder().company(company).statusName("Converted").statusCode("CONVERTED").stageType(StageType.CONVERTED).sortOrder(8).build());
                persist(LeadStatus.builder().company(company).statusName("Lost").statusCode("LOST").stageType(StageType.LOST).sortOrder(9).build());
                sourceId.put(code, persist(LeadSource.builder().company(company).sourceName("Walk-in").sourceCode("WALKIN").build()).getId());
                lostReasonId.put(code, persist(LeadLostReason.builder().company(company).reasonText("Price").build()).getId());
                for (char b : new char[]{'a', 'b'}) {
                    Slot slot = Slot.valueOf(code + b);
                    branchId.put(slot, persist(Branch.builder().company(company).name("IT Branch " + slot).code("IT-" + slot).build()).getId());
                }
            }
            for (Persona p : Persona.values()) user(org, o, p, roles.get(p));
        }
        for (Slot s : Slot.values()) {
            configId.put(s, persist(WhatsAppConfiguration.builder()
                    .company(em.getReference(Company.class, companyId.get(s.company())))
                    .branch(em.getReference(Branch.class, branchId.get(s)))
                    .displayPhoneNumber("+97140000" + s.ordinal()).phoneNumberId("it-pnid-" + s).wabaId("it-waba-" + s)
                    .accessToken("it-not-a-real-token").verifyToken("it-verify-" + s).build()).getId());
            Customer customer = newCustomer(s);
            customerId.put(s, customer.getId());
            leadId.put(s, newLead(s, customer.getId()).getId());
            WhatsAppConversation conv = newConversation(s);
            conversationId.put(s, conv.getId());
            messageId.put(s, persist(WhatsAppMessage.builder().conversation(conv).direction(MessageDirection.INBOUND)
                    .status(MessageStatus.DELIVERED).bodyText("hello from " + s).wamid("wamid.it." + s).build()).getId());
            broadcastId.put(s, newBroadcast(s, false).getId());
        }
        for (String code : companyId.keySet()) {
            companyWideBroadcastId.put(code, newBroadcast(Slot.valueOf(code + "a"), true).getId());
        }
        em.flush();
        for (String key : userId.keySet()) token.put(key, jwt.generateToken(em.find(User.class, userId.get(key))));
    }

    private Role role(Organization org, Persona p) {
        Role role = Role.builder().organization(org).roleName("IT " + p + " " + org.getCode()).roleLevel(p.level).build();
        for (String module : MODULES) {
            role.getPermissions().add(RolePermission.builder().role(role).moduleName(module)
                    .canView(true).canAdd(true).canEdit(true).canDelete(true).canExport(true)
                    .canApprove(true).canAssign(true).canPrint(true).build());
        }
        return persist(role);
    }

    private void user(Organization org, char o, Persona p, Role role) {
        User user = User.builder().organization(org).role(role)
                .email(p.name().toLowerCase().replace('_', '.') + "@org-" + Character.toLowerCase(o) + ".it.test")
                .password("{noop}not-used").firstName(p.name()).lastName("Org " + o).build();
        Long c1 = companyId.get(o + "1");
        switch (p) {
            case ORG_ADMIN -> { }
            case COMPANY_MGR -> user.getAllowedCompanies().add(em.getReference(Company.class, c1));
            default -> user.getAllowedBranches().add(em.getReference(Branch.class, branchId.get(home(o))));
        }
        userId.put(o + ":" + p, persist(user).getId());
    }

    private Customer newCustomer(Slot s) {
        String phone = phone();
        return persist(Customer.builder()
                .organization(em.getReference(Organization.class, orgId.get(s.org())))
                .company(em.getReference(Company.class, companyId.get(s.company())))
                .homeBranch(em.getReference(Branch.class, branchId.get(s)))
                .name("IT Customer " + s).mobileNumber(phone).mobileNormalized(PhoneNumberUtil.canonical(phone))
                .status(CustomerStatus.NEW).build());
    }

    private Lead newLead(Slot s, Long customer) {
        Customer c = em.find(Customer.class, customer);
        return persist(Lead.builder()
                .organization(em.getReference(Organization.class, orgId.get(s.org())))
                .company(em.getReference(Company.class, companyId.get(s.company())))
                .branch(em.getReference(Branch.class, branchId.get(s)))
                .customer(c).leadNumber("IT-LD-" + seq.incrementAndGet()).firstName("IT Lead " + s).phone(c.getMobileNumber())
                .source(em.getReference(LeadSource.class, sourceId.get(s.company())))
                .status(em.getReference(LeadStatus.class, statusId.get(s.company())))
                .build());
    }

    private WhatsAppConversation newConversation(Slot s) {
        return persist(WhatsAppConversation.builder()
                .company(em.getReference(Company.class, companyId.get(s.company())))
                .branch(em.getReference(Branch.class, branchId.get(s)))
                .receivingPhoneNumberId("it-pnid-" + s)
                .customerWaId(phone().substring(1)).customerName("IT Chat " + s)
                // The customer wrote a minute ago: the 24-hour reply window is open.
                .lastCustomerMessageEpoch(java.time.Instant.now().getEpochSecond() - 60).build());
    }

    private Broadcast newBroadcast(Slot s, boolean companyWide) {
        return persist(Broadcast.builder()
                .company(em.getReference(Company.class, companyId.get(s.company())))
                .branch(companyWide ? null : em.getReference(Branch.class, branchId.get(s)))
                .whatsappConfiguration(em.getReference(WhatsAppConfiguration.class, configId.get(s)))
                .name("IT Broadcast " + s + (companyWide ? " (company-wide)" : ""))
                .templateName("hello_world").audienceType(BroadcastAudienceType.CUSTOM_NUMBERS).build());
    }

    private <T> T persist(T entity) { em.persist(entity); return entity; }
}
