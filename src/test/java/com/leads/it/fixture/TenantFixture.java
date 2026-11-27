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

    private static final String[] MODULES = {"LEADS", "CUSTOMERS", "BROADCASTS", "WHATSAPP_LIVE_CHAT", "REPORTS_ANALYTICS", "QUOTATIONS", "DELIVERIES", "LEAD_SETTINGS", "PRODUCT_CATALOG", "WHATSAPP_TEMPLATES", "AI_ASSISTANT"};
    /** Tenant-admin rights (module 03): managers have them; agents do not, like the real "Agent" template. */
    // Module 17: WHATSAPP_AUTOMATIONS is manager-only (doc assumption: OWN roles don't configure automations).
    private static final String[] ADMIN_MODULES = {"COMPANY_BRANCH_SETTINGS", "ROLE_PERMISSION_MANAGEMENT", "USER_MANAGEMENT", "WHATSAPP_AUTOMATIONS", "AI_SETTINGS"};

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
    private final Map<String, Long> failureReasonId = new HashMap<>();
    private final Map<String, Long> companyWideBroadcastId = new HashMap<>();
    private final Map<Slot, Long> branchId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> configId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> customerId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> leadId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> conversationId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> messageId = new EnumMap<>(Slot.class);
    private final Map<Slot, Long> broadcastId = new EnumMap<>(Slot.class);
    private final Map<String, Long> userId = new HashMap<>();
    private final Map<String, Long> roleId = new HashMap<>();
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
    public Long failureReason(Slot s) { return ensureBuilt().failureReasonId.get(s.company()); }
    public Long user(char org, Persona p) { return ensureBuilt().userId.get(org + ":" + p); }
    /** The fixture role a persona of {@code org} holds. */
    public Long role(char org, Persona p) { return ensureBuilt().roleId.get(org + ":" + p); }
    public String token(char org, Persona p) { return ensureBuilt().token.get(org + ":" + p); }

    /** The slot a persona "lives" in: its own company x1 and branch x1a. */
    public static Slot home(char org) { return Slot.valueOf(org + "1a"); }

    // ---- fresh records for rows that write --------------------------------------------------

    public Long freshLead(Slot s) { return inTx(() -> newLead(s, ensureBuilt().customerId.get(s)).getId()); }
    /** A customer with one lead in {@code s}: branch-level access to a customer goes through its leads. */
    public Long freshCustomer(Slot s) { return inTx(() -> { Customer c = newCustomer(s); newLead(s, c.getId()); return c.getId(); }); }
    public Long freshBroadcast(Slot s) { return inTx(() -> newBroadcast(s, false).getId()); }
    public Long freshCompanyWideBroadcast(Slot s) { return inTx(() -> newBroadcast(s, true).getId()); }

    /**
     * Module 16 part 2: a broadcast on {@code s}'s branch using a fresh, inactive WhatsApp number. Sending it
     * succeeds (200) and the executor then marks it FAILED before any Meta call, so the matrix can have an
     * allowed send row without Meta traffic.
     */
    public Long freshBroadcastOnInactiveNumber(Slot s) {
        Long config = freshWhatsAppConfig(s);
        return inTx(() -> {
            WhatsAppConfiguration c = em.find(WhatsAppConfiguration.class, config);
            c.setIsActive(false);
            Broadcast b = newBroadcast(s, false);
            b.setWhatsappConfiguration(c);
            return b.getId();
        });
    }
    public Long freshConversation(Slot s) { return inTx(() -> newConversation(s).getId()); }

    /** A snapshot of one record's fields, for "a denied write changed nothing" probes. */
    public <T> String state(Class<T> type, Long id, java.util.function.Function<T, Object> fields) {
        return tx.execute(st -> { T e = em.find(type, id); return e == null ? "<deleted>" : String.valueOf(fields.apply(e)); });
    }

    /** A fresh lead (with its own customer) in {@code s}, assigned to {@code assignee} (null = unassigned). */
    public Long freshLeadAssignedTo(Slot s, Long assignee) {
        return inTx(() -> {
            Customer c = newCustomer(s);
            Lead lead = newLead(s, c.getId());
            if (assignee != null) lead.setAssignedTo(em.getReference(User.class, assignee));
            return lead.getId();
        });
    }

    /** Another lead of an existing customer, assigned to {@code assignee} (null = unassigned). Module 06. */
    public Long freshLeadForCustomer(Slot s, Long customerId, Long assignee) {
        return inTx(() -> {
            Lead lead = newLead(s, customerId);
            if (assignee != null) lead.setAssignedTo(em.getReference(User.class, assignee));
            return lead.getId();
        });
    }

    /**
     * Sets a lead's created_at the way the app stores times (Hibernate binding, same JDBC timezone
     * handling as @CreationTimestamp), so date-filter tests compare like with like. Module 06.
     */
    public void setLeadCreatedAt(Long leadId, java.time.LocalDateTime serverWallClock) {
        setCreatedAt("leads", leadId, serverWallClock);
    }

    /** Same, for any fixture table with created_at ({@code leads}, {@code customers}, ...). Module 07. */
    public void setCreatedAt(String table, Long id, java.time.LocalDateTime serverWallClock) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException(table);
        inTx(() -> em.createNativeQuery("UPDATE " + table + " SET created_at = :t WHERE id = :id")
                .setParameter("t", serverWallClock).setParameter("id", id).executeUpdate());
    }

    public Long customerOfLead(Long leadId) { return inTx(() -> em.find(Lead.class, leadId).getCustomer().getId()); }

    /** A fresh conversation in {@code s}: its agent and its linked lead are both optional. */
    public Long freshConversationFor(Slot s, Long agent, Long leadId) {
        return inTx(() -> {
            WhatsAppConversation c = newConversation(s);
            if (agent != null) c.setAssignedAgent(em.getReference(User.class, agent));
            if (leadId != null) c.setLead(em.getReference(Lead.class, leadId));
            return c.getId();
        });
    }

    public Long freshQuotation(Slot s, Long leadId, Long createdBy) {
        return inTx(() -> {
            Quotation q = Quotation.builder()
                    .organization(em.getReference(Organization.class, orgId.get(s.org())))
                    .company(em.getReference(Company.class, companyId.get(s.company())))
                    .lead(em.getReference(Lead.class, leadId))
                    .quotationNumber("IT-QT-" + seq.incrementAndGet())
                    .createdBy(createdBy == null ? null : em.getReference(User.class, createdBy)).build();
            return persist(q).getId();
        });
    }

    public Long freshDelivery(Slot s, Long leadId, Long assignedTo) {
        return inTx(() -> {
            Delivery d = Delivery.builder()
                    .organization(em.getReference(Organization.class, orgId.get(s.org())))
                    .company(em.getReference(Company.class, companyId.get(s.company())))
                    .branch(em.getReference(Branch.class, branchId.get(s)))
                    .lead(em.getReference(Lead.class, leadId))
                    .deliveryNumber("IT-DL-" + seq.incrementAndGet()).status(DeliveryStatus.SCHEDULED)
                    .scheduledDate(java.time.LocalDate.now().plusDays(1))
                    .recipientName("IT Recipient").recipientPhone(phone()).addressLine1("IT Street 1")
                    .assignedTo(assignedTo == null ? null : em.getReference(User.class, assignedTo)).build();
            return persist(d).getId();
        });
    }

    /** Module 09: a fresh delivery on a fresh lead of {@code s}, in {@code status}; PENDING_APPROVAL carries a close request. */
    public Long freshDeliveryIn(Slot s, DeliveryStatus status, Long assignedTo) {
        Long id = freshDelivery(s, freshLead(s), assignedTo);
        inTx(() -> {
            Delivery d = em.find(Delivery.class, id);
            d.setStatus(status);
            if (status == DeliveryStatus.PENDING_APPROVAL) d.setRequestedAction(RequestedDeliveryAction.CLOSE);
            return null;
        });
        return id;
    }

    public Long freshFailureReason(Slot s) {
        return inTx(() -> persist(DeliveryFailureReason.builder().company(companyRef(s)).reasonText("IT reason " + seq.incrementAndGet()).build()).getId());
    }

    /**
     * Module 17: a fresh DRAFT automation on {@code s}'s branch (or company-level), publishable, triggered only by
     * the exact keyword {@code keyword} (so it never fires on another test's messages once published).
     */
    public Long freshAutomation(Slot s, boolean companyLevel, String keyword) {
        return inTx(() -> persist(Automation.builder()
                .company(companyRef(s))
                .branch(companyLevel ? null : em.getReference(Branch.class, branchId.get(s)))
                .name("IT Automation " + seq.incrementAndGet())
                .triggerType(TriggerType.KEYWORD)
                .triggerJson("{\"keyword\":\"" + keyword + "\",\"matchType\":\"EXACT_MATCH\"}")
                .actionsJson("[{\"type\":\"SEND_MESSAGE\",\"messageText\":\"IT automation reply\"}]")
                .build()).getId());
    }

    /** Module 17: a finished execution of automation {@code automationId}. */
    public Long freshAutomationExecution(Long automationId) {
        return inTx(() -> persist(AutomationExecution.builder()
                .automation(em.getReference(Automation.class, automationId))
                .customerPhone("971500000000").customerName("IT Execution")
                .status(ExecutionStatus.COMPLETED).startedAt(java.time.LocalDateTime.now()).build()).getId());
    }

    /** Module 18: {@code s}'s branch has exactly one selected, active AI provider (OpenAI); returns its id. */
    public Long ensureAiProvider(Slot s) {
        return inTx(() -> {
            var existing = em.createQuery("SELECT c FROM AiProviderConfig c WHERE c.branch.id = :b AND c.isSelected = true AND c.isActive = true",
                    AiProviderConfig.class).setParameter("b", branchId.get(s)).getResultList();
            if (!existing.isEmpty()) return existing.get(0).getId();
            return persist(AiProviderConfig.builder().company(companyRef(s)).branch(em.getReference(Branch.class, branchId.get(s)))
                    .provider(AiProviderType.OPENAI).modelName("gpt-it").apiKey("it-not-a-real-key").isActive(true).isSelected(true).build()).getId();
        });
    }

    /** Module 18: an AI provider of {@code type} on {@code s}'s branch (not selected unless it is the first); returns its id. */
    public Long ensureAiProvider(Slot s, AiProviderType type) {
        ensureAiProvider(s);
        return inTx(() -> {
            var existing = em.createQuery("SELECT c FROM AiProviderConfig c WHERE c.branch.id = :b AND c.provider = :t", AiProviderConfig.class)
                    .setParameter("b", branchId.get(s)).setParameter("t", type).getResultList();
            if (!existing.isEmpty()) return existing.get(0).getId();
            return persist(AiProviderConfig.builder().company(companyRef(s)).branch(em.getReference(Branch.class, branchId.get(s)))
                    .provider(type).modelName(type.name().toLowerCase() + "-it").apiKey("it-not-a-real-key").isActive(true).isSelected(false).build()).getId();
        });
    }

    /** Module 18: gives a message a Meta media id (voice notes). */
    public void setMedia(Long messageId, String mediaId, String mime) {
        inTx(() -> { WhatsAppMessage m = em.find(WhatsAppMessage.class, messageId); m.setMediaId(mediaId); m.setMediaMimeType(mime); return null; });
    }

    /** Module 18: a cached translation of message {@code messageId}, made by {@code s}'s org admin. */
    public Long freshTranslation(Slot s, Long messageId) {
        return inTx(() -> {
            WhatsAppMessageTranslation t = new WhatsAppMessageTranslation();
            t.setMessage(em.getReference(WhatsAppMessage.class, messageId));
            t.setCompany(companyRef(s));
            t.setSourceLanguage("es");
            t.setTranslatedText("hello");
            t.setProvider("OPENAI");
            t.setModelName("gpt-it");
            t.setGeneratedByUser(em.getReference(User.class, userId.get(s.org() + ":" + Persona.ORG_ADMIN)));
            return persist(t).getId();
        });
    }

    /** Module 18: a cached conversion score for lead {@code leadId}. */
    public Long freshLeadScore(Long leadId) {
        return inTx(() -> {
            Lead lead = em.find(Lead.class, leadId);
            User someone = em.createQuery("SELECT u FROM User u WHERE u.organization.id = :o ORDER BY u.id", User.class)
                    .setParameter("o", lead.getCompany().getOrganization().getId()).setMaxResults(1).getSingleResult();
            return persist(LeadConversionScore.builder().lead(lead).company(lead.getCompany()).score(60).strengthLabel("MEDIUM")
                    .rationale("IT").basedOnMessageCount(1).generatedByUser(someone).generatedAt(java.time.LocalDateTime.now()).build()).getId();
        });
    }

    public String leadNumber(Long leadId) { return inTx(() -> em.find(Lead.class, leadId).getLeadNumber()); }

    /** Module 18: a text (or audio) inbound message in conversation {@code conversationId}. */
    public Long freshInboundMessage(Long conversationId, String type, String body) {
        return inTx(() -> persist(WhatsAppMessage.builder().conversation(em.getReference(WhatsAppConversation.class, conversationId))
                .direction(MessageDirection.INBOUND).messageType(type).bodyText(body).status(MessageStatus.DELIVERED)
                .wamid("wamid.it-in-" + seq.incrementAndGet()).build()).getId());
    }

    /** Module 15: a fresh saved reply of {@code s}'s company. */
    public Long freshSavedReply(Slot s) {
        return inTx(() -> {
            SavedReply r = new SavedReply();
            r.setCompanyId(companyId.get(s.company()));
            r.setTitle("IT Reply " + seq.incrementAndGet());
            r.setBody("IT saved reply body");
            return persist(r).getId();
        });
    }

    /** Module 14: a fresh WhatsApp holiday of {@code s}'s company, applying to {@code s}'s branch. */
    public Long freshHoliday(Slot s) {
        return inTx(() -> {
            WhatsAppHoliday h = WhatsAppHoliday.builder().company(companyRef(s)).name("IT Holiday " + seq.incrementAndGet())
                    .startDate(java.time.LocalDate.of(2030, 1, 1)).endDate(java.time.LocalDate.of(2030, 1, 2)).build();
            h.getBranches().add(em.getReference(Branch.class, branchId.get(s)));
            return persist(h).getId();
        });
    }

    /** Module 12: gives the slot's WhatsApp connection an app secret, so a test can sign a webhook with it. */
    public void setAppSecret(Slot s, String secret) {
        inTx(() -> { em.find(WhatsAppConfiguration.class, configId.get(s)).setAppSecret(secret); return null; });
    }

    /** Module 12: the customer phone (wa_id) of a conversation. */
    public String waIdOf(Long conversationId) {
        return inTx(() -> em.find(WhatsAppConversation.class, conversationId).getCustomerWaId());
    }

    /** Module 11: a fresh WhatsApp connection on branch {@code s} (no conversations use it, so it can be deleted). */
    public Long freshWhatsAppConfig(Slot s) {
        return inTx(() -> { int n = seq.incrementAndGet(); return persist(WhatsAppConfiguration.builder()
                .company(em.getReference(Company.class, companyId.get(s.company())))
                .branch(em.getReference(Branch.class, branchId.get(s)))
                .phoneNumberId("it-fresh-pnid-" + n).wabaId("it-fresh-waba-" + n)
                .accessToken("it-not-a-real-token").verifyToken("it-verify-fresh-" + n).build()).getId(); });
    }

    // ---- module 03: fresh tenant-admin records for rows that write ------------------------------

    /** A company in {@code s}'s organization (with the app's default lead settings not needed by tests). */
    public Long freshCompany(Slot s) {
        return inTx(() -> {
            int n = seq.incrementAndGet();
            Company c = Company.builder().organization(em.getReference(Organization.class, orgId.get(s.org())))
                    .name("IT Fresh Company " + n).code("IT-FC-" + n).build();
            return persist(c).getId();
        });
    }

    /** A branch in {@code s}'s company; {@code active = false} for reactivate rows. */
    public Long freshBranch(Slot s, boolean active) {
        return inTx(() -> {
            int n = seq.incrementAndGet();
            Branch b = persist(Branch.builder().company(em.getReference(Company.class, companyId.get(s.company())))
                    .name("IT Fresh Branch " + n).code("IT-FB-" + n).isActive(active).build());
            // In an org's home slot (A1a, B1a, S1a, P1a) the branch-level personas get the new branch,
            // so their ALLOWED rows can act on it; elsewhere (A1b, A2a) it stays outside their scope.
            if (s == home(s.org())) {
                for (Persona p : Persona.values()) {
                    Long uid = userId.get(s.org() + ":" + p);
                    if (p.level == RoleLevel.BRANCH && uid != null) em.find(User.class, uid).getAllowedBranches().add(b);
                }
            }
            return b.getId();
        });
    }

    /** A BRANCH-level role in {@code s}'s organization with LEADS view only. */
    public Long freshRole(Slot s) {
        return inTx(() -> {
            Role r = Role.builder().organization(em.getReference(Organization.class, orgId.get(s.org())))
                    .roleName("IT Fresh Role " + seq.incrementAndGet()).roleLevel(RoleLevel.BRANCH).build();
            r.getPermissions().add(RolePermission.builder().role(r).moduleName("LEADS").canView(true).build());
            return persist(r).getId();
        });
    }

    /** An agent-like user in branch {@code s} (role = that org's AGENT_OWN_1 role). */
    public Long freshUser(Slot s) {
        return inTx(() -> {
            User u = User.builder().organization(em.getReference(Organization.class, orgId.get(s.org())))
                    .role(em.getReference(Role.class, roleId.get(s.org() + ":" + Persona.AGENT_OWN_1)))
                    .email("fresh" + seq.incrementAndGet() + "@org-" + Character.toLowerCase(s.org()) + ".it.test")
                    .password("{noop}not-used").firstName("Fresh").lastName("User").build();
            u.getAllowedCompanies().add(em.getReference(Company.class, companyId.get(s.company())));
            u.getAllowedBranches().add(em.getReference(Branch.class, branchId.get(s)));
            return persist(u).getId();
        });
    }

    public Long freshTrustedIp(Slot s) {
        return inTx(() -> persist(BranchTrustedIp.builder().branch(em.getReference(Branch.class, branchId.get(s)))
                .ipAddress("198.51.100." + (seq.incrementAndGet() % 250)).label("IT").build()).getId());
    }

    /** A unique email for create-user rows. */
    // ---- module 04: fresh lead-settings records for rows that write -----------------------------

    private Company companyRef(Slot s) { return em.getReference(Company.class, companyId.get(s.company())); }

    /** A custom mid-pipeline status (NEGOTIATION: not an anchor, not role-locked, so it can be edited and deleted). */
    public Long freshStatus(Slot s) {
        return inTx(() -> { int n = seq.incrementAndGet(); return persist(LeadStatus.builder().company(companyRef(s))
                .statusName("IT Status " + n).statusCode("IT_ST_" + n).stageType(StageType.NEGOTIATION).sortOrder(5).build()).getId(); });
    }
    /** Inactive, so it never makes "Lead Purpose" mandatory for other modules' lead rows. */
    public Long freshPurpose(Slot s) {
        return inTx(() -> persist(LeadPurpose.builder().company(companyRef(s)).purposeName("IT Purpose " + seq.incrementAndGet()).isActive(false).build()).getId());
    }
    public Long freshSource(Slot s) {
        return inTx(() -> { int n = seq.incrementAndGet(); return persist(LeadSource.builder().company(companyRef(s))
                .sourceName("IT Source " + n).sourceCode("IT_SRC_" + n).build()).getId(); });
    }
    public Long freshLostReason(Slot s) {
        return inTx(() -> persist(LeadLostReason.builder().company(companyRef(s)).reasonText("IT Reason " + seq.incrementAndGet()).build()).getId());
    }
    public Long freshDynamicField(Slot s) {
        return inTx(() -> { int n = seq.incrementAndGet(); return persist(DynamicFieldDefinition.builder().company(companyRef(s))
                .fieldName("it_field_" + n).fieldLabel("IT Field " + n).fieldType(FieldType.TEXT).build()).getId(); });
    }

    /** Probe state of a predefined field config ("none" until the company first saves or reads one). */
    public String predefinedFieldState(Long company, String fieldKey) {
        return inTx(() -> em.createQuery("select c from PredefinedFieldConfig c where c.company.id = :c and c.fieldKey = :k", PredefinedFieldConfig.class)
                .setParameter("c", company).setParameter("k", PredefinedLeadField.valueOf(fieldKey)).getResultList().stream()
                .map(c -> c.getIsVisible() + "|" + c.getIsMandatory()).toList().toString());
    }

    /** Probe state of a company's lead settings row ("[]" until it exists). */
    public String companyLeadSettingsState(Long company) {
        return inTx(() -> em.createQuery("select s from CompanyLeadSettings s where s.company.id = :c", CompanyLeadSettings.class)
                .setParameter("c", company).getResultList().stream()
                .map(x -> x.getLinkProductEnabled() + "|" + x.getAutoAssignEnabled() + "|" + x.getAutoAssignStrategy() + "|" + x.getUpdatedAt()).toList().toString());
    }

    // ---- module 05: products -------------------------------------------------------------------

    public Long freshProduct(Slot s) {
        return inTx(() -> { int n = seq.incrementAndGet(); return persist(ProductMaster.builder().company(companyRef(s))
                .productCode("IT-PRD-" + n).productName("IT Product " + n).unitPrice(new java.math.BigDecimal("10.00")).build()).getId(); });
    }

    /** Puts {@code product} on a fresh lead of slot {@code s} (a lead_products row). */
    public void useProductOnLead(Slot s, Long product) {
        Long lead = freshLead(s);
        inTx(() -> persist(LeadProduct.builder().lead(em.getReference(Lead.class, lead)).product(em.getReference(ProductMaster.class, product)).build()));
    }

    /** Puts {@code product} on a fresh quotation of slot {@code s} (a quotation_items row). */
    public void useProductOnQuotation(Slot s, Long product) {
        Long quotation = freshQuotation(s, freshLead(s), null);
        inTx(() -> persist(QuotationItem.builder().quotation(em.getReference(Quotation.class, quotation))
                .product(em.getReference(ProductMaster.class, product)).itemName("IT item").build()));
    }

    public String freshEmail(Slot s) { return "new" + seq.incrementAndGet() + "@org-" + Character.toLowerCase(s.org()) + ".it.test"; }

    public boolean exists(Class<?> type, Long id) { return Boolean.TRUE.equals(tx.execute(st -> em.find(type, id) != null)); }

    public <T> T inTx(Supplier<T> work) { ensureBuilt(); return tx.execute(st -> work.get()); }

    /** A unique, valid UAE mobile number. */
    public String phone() { return "+97150" + String.format("%07d", seq.incrementAndGet()); }

    // ---- build ----------------------------------------------------------------------------

    private void build() {
        for (char o : new char[]{'A', 'B', 'S', 'P'}) {
            boolean stateOrg = o == 'S' || o == 'P';
            Organization org = Organization.builder().name("IT Org " + o).code("IT-ORG-" + o).build();
            // STATE rows: S is SUSPENDED; P has been PAST_DUE for 30 days (past the 7-day grace).
            if (o == 'S') { org.setStatus(OrganizationStatus.SUSPENDED); org.setStatusChangedAt(java.time.LocalDateTime.now()); }
            if (o == 'P') { org.setStatus(OrganizationStatus.PAST_DUE); org.setStatusChangedAt(java.time.LocalDateTime.now().minusDays(30)); }
            persist(org);
            orgId.put(o, org.getId());
            Map<Persona, Role> roles = new EnumMap<>(Persona.class);
            for (Persona p : Persona.values()) { roles.put(p, role(org, p)); roleId.put(o + ":" + p, roles.get(p).getId()); }

            for (int c = 1; c <= (stateOrg ? 1 : 2); c++) {
                String code = o + "" + c;
                Company company = persist(Company.builder().organization(org).name("IT Company " + code).code("IT-" + code).build());
                companyId.put(code, company.getId());
                statusId.put(code, persist(LeadStatus.builder().company(company).statusName("New").statusCode("NEW")
                        .stageType(StageType.OPEN).isDefault(true).build()).getId());
                persist(LeadStatus.builder().company(company).statusName("Converted").statusCode("CONVERTED").stageType(StageType.CONVERTED).sortOrder(8).build());
                persist(LeadStatus.builder().company(company).statusName("Lost").statusCode("LOST").stageType(StageType.LOST).sortOrder(9).build());
                sourceId.put(code, persist(LeadSource.builder().company(company).sourceName("Walk-in").sourceCode("WALKIN").build()).getId());
                lostReasonId.put(code, persist(LeadLostReason.builder().company(company).reasonText("Price").build()).getId());
                // Module 09: the delivery module is on for every fixture company, with one failure reason.
                persist(CompanyDeliverySettings.builder().company(company).deliveryModuleEnabled(true).build());
                failureReasonId.put(code, persist(DeliveryFailureReason.builder().company(company).reasonText("IT not home").build()).getId());
                for (char b : stateOrg ? new char[]{'a'} : new char[]{'a', 'b'}) {
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
        // D14: the agents' role is OWN. Inert unless own_scope.enforced is on for the org (OwnScopePrimitiveIT).
        if (p == Persona.AGENT_OWN_1 || p == Persona.AGENT_OWN_2) role.setDataScope(DataScope.OWN);
        java.util.List<String> modules = new java.util.ArrayList<>(java.util.List.of(MODULES));
        if (p != Persona.AGENT_OWN_1 && p != Persona.AGENT_OWN_2) modules.addAll(java.util.List.of(ADMIN_MODULES));
        for (String module : modules) {
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
