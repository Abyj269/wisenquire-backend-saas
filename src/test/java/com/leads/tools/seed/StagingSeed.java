package com.leads.tools.seed;

import com.leads.LeadsSystemApplication;
import com.leads.model.entity.*;
import com.leads.model.enums.RoleLevel;
import com.leads.model.enums.StageType;
import com.leads.service.LeadSettingsService;
import com.leads.util.PhoneNumberUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * Synthetic staging tenants (plan Phase 0 Step 3.3; 01-foundation Part A item 5).
 * <p>
 * TEST SCOPE ON PURPOSE: this class never ships in the WAR or the container JAR. It runs on
 * the host against the staging MySQL via {@code deploy/staging/scripts/seed.sh}.
 * <p>
 * Reference data (orgs, roles, companies with the app's own default pipeline/sources/lost
 * reasons, branches, WhatsApp configs, users) goes through JPA, so credentials are encrypted
 * by the real converters and passwords by the real encoder. Bulk rows (customers, leads,
 * conversations, messages) go through JDBC batches with explicit ids.
 * <p>
 * Deterministic: a fixed random seed per tenant, so every reset produces the same data
 * (dates are relative to the run time). Guards: SEED_TARGET=staging, a staging/ci database
 * name, the credential key in the environment, and an empty organizations table.
 */
public final class StagingSeed {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Dubai"); // container TZ; LocalDateTime columns are server wall-clock
    private static final int BATCH = 5000;
    private static final String[] MODULES = {"LEADS", "CUSTOMERS", "QUOTATIONS", "DELIVERIES", "REPORTS_ANALYTICS",
            "WHATSAPP_LIVE_CHAT", "WHATSAPP_TEMPLATES", "WHATSAPP_AUTOMATIONS", "BROADCASTS", "LEAD_SETTINGS",
            "PRODUCT_CATALOG", "AI_ASSISTANT", "AI_SETTINGS", "COMPANY_BRANCH_SETTINGS", "USER_MANAGEMENT",
            "ROLE_PERMISSION_MANAGEMENT"};
    private static final Set<String> AGENT_MODULES = Set.of("LEADS", "CUSTOMERS", "QUOTATIONS", "DELIVERIES",
            "WHATSAPP_LIVE_CHAT", "AI_ASSISTANT");
    private static final String[] FIRST = {"Aarav", "Vivaan", "Aditya", "Priya", "Ananya", "Diya", "Rohan", "Kavya", "Arjun", "Meera",
            "Omar", "Fatima", "Yousef", "Aisha", "Khalid", "Mariam", "Hassan", "Noura", "Saeed", "Layla", "Rahul", "Sneha", "Imran", "Zainab"};
    private static final String[] LAST = {"Sharma", "Iyer", "Nair", "Menon", "Reddy", "Gupta", "Khan", "Patel", "Al Mansoori", "Al Hashimi",
            "Al Balushi", "Al Harthy", "Al Qahtani", "Al Thani", "Al Khalifa", "Al Sabah", "Pillai", "Das", "Joshi", "Rao"};
    private static final String[] CITIES = {"Dubai", "Sharjah", "Abu Dhabi", "Muscat", "Riyadh", "Doha", "Manama", "Kuwait City", "Kochi", "Bengaluru", "Mumbai", "Chennai"};

    record StatusRef(long id, StageType stage, boolean isDefault) { }
    record BranchRef(long id, String pnid, long companyId, long mgr, long agent1, long agent2) { }
    record CompanyRef(long id, String code, String zone, List<BranchRef> branches, List<StatusRef> statuses, List<Long> sources) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager em;
    private final LeadSettingsService leadSettings;
    private final String passwordHash;
    private final List<String> personas = new ArrayList<>();
    private final LocalDateTime now = LocalDateTime.now(SERVER_ZONE).withNano(0);
    private long phoneCounter = 1_000_000;

    private StagingSeed(ConfigurableApplicationContext ctx, String userPassword) {
        this.jdbc = ctx.getBean(JdbcTemplate.class);
        this.tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        this.em = SharedEntityManagerCreator.createSharedEntityManager(ctx.getBean(EntityManagerFactory.class));
        this.leadSettings = ctx.getBean(LeadSettingsService.class);
        this.passwordHash = ctx.getBean(PasswordEncoder.class).encode(userPassword);
    }

    public static void main(String[] args) throws Exception {
        String scale = arg(args, "--scale", "full");
        Map<String, String> env = System.getenv();
        guard(env);
        SeedPlan plan = SeedPlan.forScale(scale);
        long started = System.currentTimeMillis();
        int exit = 0;
        // Command-line arguments, NOT SpringApplicationBuilder.properties(): those are defaults with
        // the lowest precedence, so application.yml would win (spring.profiles.active=windows-dev ->
        // the local dev database on localhost:3306, with Flyway on).
        String[] cli = springProperties(env).entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(LeadsSystemApplication.class)
                .web(WebApplicationType.SERVLET).run(cli)) {
            StagingSeed seed = new StagingSeed(ctx, env.get("SEED_USER_PASSWORD"));
            seed.verifyTarget(env, ctx);
            Long orgs = seed.jdbc.queryForObject("SELECT COUNT(*) FROM organizations", Long.class);
            if (orgs != null && orgs > 0) {
                throw new IllegalStateException("organizations is not empty (" + orgs + " rows). Seed only a fresh database: run scripts/reset.sh.");
            }
            for (SeedPlan.Tenant t : plan.tenants()) seed.tenant(t);
            seed.writePersonas(Path.of(arg(args, "--personas", "target/staging-seed/personas.csv")));
            System.out.printf("Seed done (scale=%s): %d tenants, %d users, %s in %ds%n", scale, plan.tenants().size(),
                    seed.personas.size(), seed.counts(), (System.currentTimeMillis() - started) / 1000);
        } catch (RuntimeException e) {
            System.err.println("SEED FAILED: " + e.getMessage());
            e.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    // ---- guards and context ------------------------------------------------------------------

    private static void guard(Map<String, String> env) {
        if (!"staging".equals(env.get("SEED_TARGET"))) throw new IllegalStateException("Set SEED_TARGET=staging (refusing to seed anything else).");
        String db = env.getOrDefault("DB_NAME", "");
        if (!db.matches("wisenquire_(staging|ci)[a-z0-9_]*")) throw new IllegalStateException("DB_NAME must be wisenquire_staging* or wisenquire_ci*, got '" + db + "'.");
        if (isBlank(env.get("ADS_CREDENTIALS_ENCRYPTION_KEY"))) {
            throw new IllegalStateException("ADS_CREDENTIALS_ENCRYPTION_KEY must be the API's key, or the API cannot decrypt the seeded WhatsApp credentials.");
        }
        if (isBlank(env.get("SEED_USER_PASSWORD")) || env.get("SEED_USER_PASSWORD").length() < 12) throw new IllegalStateException("Set SEED_USER_PASSWORD (12+ chars).");
        if (isBlank(env.get("DB_PASSWORD"))) throw new IllegalStateException("Set DB_PASSWORD.");
        if (!"Asia/Dubai".equals(TimeZone.getDefault().getID())) {
            throw new IllegalStateException("Run with -Duser.timezone=Asia/Dubai (the container's zone), got " + TimeZone.getDefault().getID());
        }
    }

    private static Map<String, Object> springProperties(Map<String, String> env) {
        String url = "jdbc:mysql://" + env.getOrDefault("DB_HOST", "127.0.0.1") + ":" + env.getOrDefault("DB_PORT", "3307") + "/" + env.get("DB_NAME")
                + "?sslMode=REQUIRED&serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true&rewriteBatchedStatements=true";
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("spring.profiles.active", "staging-seed");
        p.put("spring.main.banner-mode", "off");
        // The security config needs Spring MVC, so the context is a web app: random port, loopback only, never called.
        p.put("server.port", "0");
        p.put("server.address", "127.0.0.1");
        p.put("management.server.port", "-1");
        p.put("spring.datasource.url", url);
        p.put("spring.datasource.username", env.getOrDefault("DB_USERNAME", "wisenquire"));
        p.put("spring.datasource.password", env.get("DB_PASSWORD"));
        p.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        p.put("spring.flyway.enabled", "false");
        p.put("spring.jpa.hibernate.ddl-auto", "validate");
        p.put("app.security.jwt.secret", hex(48)); // unused by the seed, but the context needs one
        p.put("ads.sync.cron", "-");
        p.put("broadcast.scheduler.check-ms", "86400000");
        p.put("whatsapp.receipts.reconcile-ms", "86400000");
        p.put("whatsapp.receipts.cleanup-ms", "86400000");
        p.put("logging.level.root", "WARN");
        return p;
    }

    /** Second guard, before any write: the context really is connected to the staging database. */
    private void verifyTarget(Map<String, String> env, ConfigurableApplicationContext ctx) {
        String url = ctx.getEnvironment().getProperty("spring.datasource.url", "");
        String expected = "//" + env.getOrDefault("DB_HOST", "127.0.0.1") + ":" + env.getOrDefault("DB_PORT", "3307") + "/" + env.get("DB_NAME") + "?";
        String actualDb = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!url.contains(expected) || !env.get("DB_NAME").equals(actualDb)) {
            throw new IllegalStateException("Connected to the wrong database (url=" + url + ", database=" + actualDb + "); refusing to write.");
        }
        if (Arrays.asList(ctx.getEnvironment().getActiveProfiles()).contains("windows-dev") || Arrays.asList(ctx.getEnvironment().getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("Unexpected active profile " + Arrays.toString(ctx.getEnvironment().getActiveProfiles()) + "; refusing to write.");
        }
        System.out.println("Target verified: " + actualDb + " via " + url.substring(0, url.indexOf('?')));
    }

    // ---- one tenant ---------------------------------------------------------------------------

    private void tenant(SeedPlan.Tenant t) {
        long t0 = System.currentTimeMillis();
        Random rnd = new Random(42L * 31 + t.code().hashCode());
        List<CompanyRef> companies = tx.execute(s -> reference(t));
        bulk(t, companies, rnd);
        System.out.printf("  %s %-6s companies=%d branches=%d leads=%d messages=%d (%ds)%n", t.code(), t.size(), t.companies().size(),
                t.branchCount(), t.leads(), t.messages(), (System.currentTimeMillis() - t0) / 1000);
    }

    private List<CompanyRef> reference(SeedPlan.Tenant t) {
        String tc = t.code();
        Organization org = persist(Organization.builder().name("Staging " + tc.toUpperCase() + " (" + t.size() + ")").code("STG-" + tc.toUpperCase()).build());
        Role orgAdmin = role(org, "Org Admin", RoleLevel.ORGANIZATION, m -> true);
        Role companyMgr = role(org, "Company Manager", RoleLevel.COMPANY, m -> true);
        Role branchMgr = role(org, "Branch Manager", RoleLevel.BRANCH, m -> !m.startsWith("ROLE_") && !m.equals("USER_MANAGEMENT"));
        // "Agent" template: BRANCH level. D14 adds data_scope=OWN (01 Part B item 8); until then agents see the whole branch.
        Role agent = role(org, "Agent", RoleLevel.BRANCH, AGENT_MODULES::contains);
        user(org, orgAdmin, "org.admin@" + tc + ".staging.test", "Org", "Admin", List.of(), List.of(), tc, "ORG_ADMIN", "", "", "");

        List<CompanyRef> refs = new ArrayList<>();
        for (SeedPlan.Company c : t.companies()) {
            boolean india = c.zone().equals("Asia/Kolkata");
            Company company = persist(Company.builder().organization(org).name("Staging " + tc.toUpperCase() + " " + c.code().toUpperCase())
                    .code("STG-" + tc.toUpperCase() + "-" + c.code().toUpperCase()).timezone(c.zone()).currency(india ? "INR" : "AED").build());
            leadSettings.seedDefaultPipeline(company);
            leadSettings.seedDefaultSources(company);
            leadSettings.seedDefaultLostReasons(company);
            user(org, companyMgr, "company.mgr." + c.code() + "@" + tc + ".staging.test", "Company", "Manager " + c.code(),
                    List.of(company), List.of(), tc, "COMPANY_MGR", c.code(), "", c.zone());
            List<BranchRef> branches = new ArrayList<>();
            for (int b = 1; b <= c.branches(); b++) {
                String bc = c.code() + "b" + b;
                Branch branch = persist(Branch.builder().company(company).name("Branch " + bc.toUpperCase()).code("STG-" + tc.toUpperCase() + "-" + bc.toUpperCase()).build());
                String pnid = "stg-" + tc + "-" + bc;
                persist(WhatsAppConfiguration.builder().company(company).branch(branch).phoneNumberId(pnid).wabaId("stg-waba-" + tc + "-" + bc)
                        .appId("stg-app-" + tc).accessToken("staging-mock-token-" + pnid).verifyToken("stg-verify-" + pnid)
                        .appSecret("staging-app-secret-" + pnid).displayPhoneNumber("+97140" + String.format("%07d", Math.abs(pnid.hashCode()) % 10_000_000)).build());
                long mgr = user(org, branchMgr, "branch.mgr." + bc + "@" + tc + ".staging.test", "Branch", "Manager " + bc, List.of(), List.of(branch), tc, "BRANCH_MGR", c.code(), bc, c.zone());
                long a1 = user(org, agent, "agent1." + bc + "@" + tc + ".staging.test", "Agent One", bc, List.of(), List.of(branch), tc, "AGENT_OWN_1", c.code(), bc, c.zone());
                long a2 = user(org, agent, "agent2." + bc + "@" + tc + ".staging.test", "Agent Two", bc, List.of(), List.of(branch), tc, "AGENT_OWN_2", c.code(), bc, c.zone());
                branches.add(new BranchRef(branch.getId(), pnid, company.getId(), mgr, a1, a2));
            }
            em.flush();
            List<StatusRef> statuses = jdbc.query("SELECT id, stage_type, is_default FROM lead_statuses WHERE company_id = ? AND is_active = 1 ORDER BY sort_order",
                    (rs, i) -> new StatusRef(rs.getLong(1), StageType.valueOf(rs.getString(2)), rs.getBoolean(3)), company.getId());
            List<Long> sources = jdbc.queryForList("SELECT id FROM lead_sources WHERE company_id = ? AND is_active = 1", Long.class, company.getId());
            if (statuses.isEmpty() || sources.isEmpty()) throw new IllegalStateException("default pipeline/sources missing for " + company.getCode());
            refs.add(new CompanyRef(company.getId(), c.code(), c.zone(), branches, statuses, sources));
        }
        return refs;
    }

    private Role role(Organization org, String name, RoleLevel level, java.util.function.Predicate<String> module) {
        Role role = Role.builder().organization(org).roleName(name).roleLevel(level).isSystemRole(false)
                .description("Staging seed role (" + level + ")").build();
        boolean agent = name.equals("Agent");
        for (String m : MODULES) {
            if (!module.test(m)) continue;
            role.getPermissions().add(RolePermission.builder().role(role).moduleName(m)
                    .canView(true).canAdd(true).canEdit(true).canDelete(!agent).canExport(!agent)
                    .canApprove(!agent).canAssign(!agent).canPrint(true).build());
        }
        return persist(role);
    }

    private long user(Organization org, Role role, String email, String first, String last, List<Company> companies, List<Branch> branches,
                      String tenant, String persona, String company, String branch, String zone) {
        User u = User.builder().organization(org).role(role).email(email).password(passwordHash).firstName(first).lastName(last)
                .phone(nextPhone("Asia/Dubai")).build();
        u.getAllowedCompanies().addAll(companies);
        u.getAllowedBranches().addAll(branches);
        persist(u);
        personas.add(String.join(",", tenant, persona, email, company, branch, zone));
        return u.getId();
    }

    // ---- bulk rows ---------------------------------------------------------------------------

    private void bulk(SeedPlan.Tenant t, List<CompanyRef> companies, Random rnd) {
        long customerId = nextId("customers"), leadId = nextId("leads"), convId = nextId("whatsapp_conversations"), msgId = nextId("whatsapp_messages");
        Long orgId = jdbc.queryForObject("SELECT organization_id FROM companies WHERE id = ?", Long.class, companies.get(0).id());
        List<BranchRef> allBranches = companies.stream().flatMap(c -> c.branches().stream()).toList();
        Map<Long, CompanyRef> companyOf = new HashMap<>();
        companies.forEach(c -> c.branches().forEach(b -> companyOf.put(b.id(), c)));

        // customers: spread across branches (home branch), weighted equally
        record Cust(long id, BranchRef home, String name, String mobile, LocalDateTime created) { }
        List<Cust> customers = new ArrayList<>(t.customers());
        for (int i = 0; i < t.customers(); i++) {
            BranchRef home = allBranches.get(i % allBranches.size());
            String name = FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)];
            customers.add(new Cust(customerId + i, home, name, nextPhone(companyOf.get(home.id()).zone()), daysAgo(rnd, 180)));
        }

        // leads: each belongs to a customer; mostly at the customer's home branch
        int[] total = new int[customers.size()], converted = new int[customers.size()];
        LocalDateTime[] firstLead = new LocalDateTime[customers.size()], lastLead = new LocalDateTime[customers.size()];
        List<Object[]> leadRows = new ArrayList<>(t.leads());
        Map<Integer, Long> leadOfCustomer = new HashMap<>();
        Map<Long, Integer> boundaryUsed = new HashMap<>();
        for (int i = 0; i < t.leads(); i++) {
            int ci = i < customers.size() ? i : rnd.nextInt(customers.size());
            Cust c = customers.get(ci);
            CompanyRef comp = companyOf.get(c.home().id());
            BranchRef br = rnd.nextInt(10) < 8 ? c.home() : comp.branches().get(rnd.nextInt(comp.branches().size()));
            StatusRef st = comp.statuses().get(rnd.nextInt(comp.statuses().size()));
            int a = rnd.nextInt(10);
            Long assignee = a < 4 ? Long.valueOf(br.agent1()) : a < 7 ? Long.valueOf(br.agent2()) : null; // 40% agent 1, 30% agent 2, 30% unassigned
            LocalDateTime created = c.created().plusDays(rnd.nextInt(20)).withHour(8 + rnd.nextInt(12));
            // Golden timezone rows: the first 10 leads of each company straddle the company's local midnight.
            int used = boundaryUsed.merge(comp.id(), 1, Integer::sum);
            if (used <= 10) created = localMidnightEdge(comp.zone(), used);
            if (created.isAfter(now)) created = now.minusMinutes(rnd.nextInt(600) + 1);
            boolean isConverted = st.stage() == StageType.CONVERTED;
            long id = leadId + i;
            leadOfCustomer.putIfAbsent(ci, id);
            total[ci]++;
            if (isConverted) converted[ci]++;
            firstLead[ci] = firstLead[ci] == null || created.isBefore(firstLead[ci]) ? created : firstLead[ci];
            lastLead[ci] = lastLead[ci] == null || created.isAfter(lastLead[ci]) ? created : lastLead[ci];
            String[] parts = c.name().split(" ", 2);
            leadRows.add(new Object[]{id, orgId, comp.id(), br.id(), c.id(), t.code().toUpperCase() + "-LD-" + String.format("%07d", i + 1),
                    parts[0], parts.length > 1 ? parts[1] : null, c.mobile(), c.mobile(), null,
                    comp.sources().get(rnd.nextInt(comp.sources().size())), st.id(), assignee, assignee == null ? null : Long.valueOf(br.mgr()),
                    isConverted, isConverted ? created.plusDays(3) : null, BigDecimal.valueOf(500 + rnd.nextInt(50_000)), created, created, 0L});
        }

        List<Object[]> customerRows = new ArrayList<>(customers.size());
        for (int i = 0; i < customers.size(); i++) {
            Cust c = customers.get(i);
            CompanyRef comp = companyOf.get(c.home().id());
            String status = converted[i] > 0 ? "CONVERTED" : total[i] > 1 ? "EXISTING" : total[i] == 1 ? "NEW" : "NON_CONVERTED";
            customerRows.add(new Object[]{c.id(), orgId, comp.id(), c.home().id(), comp.sources().get(0), c.name(), c.mobile(),
                    PhoneNumberUtil.canonical(c.mobile()), c.mobile(), null, CITIES[(int) (c.id() % CITIES.length)], status,
                    total[i], converted[i], false, firstLead[i], lastLead[i], c.created(), c.created(), 0L});
        }

        // conversations: one per customer (the first N), at the customer's home branch number
        List<Object[]> convRows = new ArrayList<>(t.conversations());
        List<Object[]> msgRows = new ArrayList<>(t.messages());
        int perConv = Math.max(1, t.messages() / Math.max(1, t.conversations()));
        int extra = t.messages() - perConv * t.conversations();
        long msg = msgId;
        for (int i = 0; i < t.conversations(); i++) {
            Cust c = customers.get(i % customers.size());
            BranchRef br = c.home();
            CompanyRef comp = companyOf.get(br.id());
            int a = rnd.nextInt(10);
            Long agent = a < 4 ? Long.valueOf(br.agent1()) : a < 7 ? Long.valueOf(br.agent2()) : null;
            int count = perConv + (i < extra ? 1 : 0);
            // 30% of chats had a customer message in the last 12 h: their 24-hour reply window is open.
            LocalDateTime lastCustomer = rnd.nextInt(10) < 3 ? now.minusMinutes(rnd.nextInt(720) + 1) : now.minusDays(2 + rnd.nextInt(60));
            LocalDateTime start = lastCustomer.minusMinutes((long) count * 7);
            LocalDateTime last = start;
            String lastText = null;
            for (int m = 0; m < count; m++) {
                boolean inbound = m % 2 == 0 || m == count - 1; // the customer writes first and last
                last = start.plusMinutes(m * 7L);
                if (i < 10 && m == 0) last = localMidnightEdge(comp.zone(), i + 1).isAfter(now) ? last : localMidnightEdge(comp.zone(), i + 1);
                lastText = inbound ? "Hi, I'm interested (" + (m + 1) + ")" : "Thanks for your message, how can we help? (" + (m + 1) + ")";
                msgRows.add(new Object[]{msg, convId + i, "wamid.STG." + t.code() + "." + msg, inbound ? "INBOUND" : "OUTBOUND", "TEXT", lastText,
                        inbound ? "DELIVERED" : (rnd.nextBoolean() ? "READ" : "DELIVERED"), false, false, inbound ? null : (agent != null ? agent : Long.valueOf(br.mgr())), last});
                msg++;
            }
            long epoch = lastCustomer.atZone(SERVER_ZONE).toEpochSecond();
            convRows.add(new Object[]{convId + i, comp.id(), br.id(), br.pnid(), digits(c.mobile()), c.name(), leadOfCustomer.get(i % customers.size()),
                    agent, lastText, last, epoch, rnd.nextInt(4), rnd.nextInt(10) < 8 ? "OPEN" : "RESOLVED", false, false, false, start, last, 0L});
        }

        batch("INSERT INTO customers (id, organization_id, company_id, home_branch_id, initial_source_id, name, mobile_number, mobile_normalized,"
                + " whatsapp_number, email, city, status, total_leads_count, converted_leads_count, is_blocked, first_lead_at, last_lead_at,"
                + " created_at, updated_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", customerRows);
        batch("INSERT INTO leads (id, organization_id, company_id, branch_id, customer_id, lead_number, first_name, last_name, phone,"
                + " whatsapp_number, email, source_id, status_id, assigned_to_id, assigned_by_id, is_converted, converted_at,"
                + " estimated_deal_value, created_at, updated_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", leadRows);
        batch("INSERT INTO whatsapp_conversations (id, company_id, branch_id, receiving_phone_number_id, customer_wa_id, customer_name,"
                + " lead_id, assigned_agent_id, last_message_text, last_message_at, last_customer_message_epoch, unread_count, chat_status,"
                + " is_from_ad, pinned, manually_unread, created_at, updated_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", convRows);
        batch("INSERT INTO whatsapp_messages (id, conversation_id, wamid, direction, message_type, body_text, status, is_auto_reply,"
                + " is_from_ad, sent_by_user_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", msgRows);
    }

    /** Edge rows for the golden timezone tests: 23:55 / 23:59 the evening before and 00:00 / 00:05 today, company-local, as server wall-clock. */
    private LocalDateTime localMidnightEdge(String zone, int n) {
        ZoneId z = ZoneId.of(zone);
        LocalDate today = now.atZone(SERVER_ZONE).withZoneSameInstant(z).toLocalDate();
        LocalTime[] times = {LocalTime.of(23, 55), LocalTime.of(23, 59), LocalTime.of(0, 0), LocalTime.of(0, 5)};
        LocalTime time = times[(n - 1) % times.length];
        LocalDate day = time.getHour() == 23 ? today.minusDays(1) : today;
        return ZonedDateTime.of(day, time, z).withZoneSameInstant(SERVER_ZONE).toLocalDateTime();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private void batch(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += BATCH) {
            List<Object[]> chunk = rows.subList(i, Math.min(rows.size(), i + BATCH));
            tx.executeWithoutResult(s -> jdbc.batchUpdate(sql, chunk));
        }
    }

    private long nextId(String table) {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return (max == null ? 0 : max) + 1;
    }

    private String counts() {
        Function<String, Long> c = t -> jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class);
        return "leads=" + c.apply("leads") + " customers=" + c.apply("customers") + " conversations=" + c.apply("whatsapp_conversations")
                + " messages=" + c.apply("whatsapp_messages");
    }

    /** Unique, valid-looking mobile per call, in the company's country. */
    private String nextPhone(String zone) {
        long n = phoneCounter++;
        return switch (zone) {
            case "Asia/Kolkata" -> "+919" + String.format("%09d", n);
            case "Asia/Muscat" -> "+9689" + String.format("%07d", n % 10_000_000);
            case "Asia/Riyadh" -> "+9665" + String.format("%08d", n);
            case "Asia/Qatar" -> "+9745" + String.format("%07d", n % 10_000_000);
            case "Asia/Bahrain" -> "+9733" + String.format("%07d", n % 10_000_000);
            case "Asia/Kuwait" -> "+9656" + String.format("%07d", n % 10_000_000);
            default -> "+9715" + String.format("%08d", n);
        };
    }

    private LocalDateTime daysAgo(Random rnd, int maxDays) {
        return now.minusDays(1 + rnd.nextInt(maxDays)).withHour(8 + rnd.nextInt(12)).withMinute(rnd.nextInt(60)).withSecond(0);
    }

    private void writePersonas(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        List<String> lines = new ArrayList<>();
        lines.add("tenant,persona,email,company,branch,timezone");
        lines.addAll(personas);
        Files.write(file, lines);
        System.out.println("Personas: " + file.toAbsolutePath() + " (password: SEED_USER_PASSWORD from .env; OTP in Mailpit)");
    }

    private <T> T persist(T entity) { em.persist(entity); return entity; }

    private static String digits(String phone) { return phone.replaceAll("\\D", ""); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String hex(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return HexFormat.of().formatHex(b); }

    private static String arg(String[] args, String name, String fallback) {
        for (String a : args) if (a.startsWith(name + "=")) return a.substring(name.length() + 1);
        return fallback;
    }

}
