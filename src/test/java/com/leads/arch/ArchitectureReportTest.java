package com.leads.arch;

import com.leads.model.entity.*;
import com.leads.service.TenantAccess;
import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan D3-3 / 01 Part A item 7: ArchUnit rules a-d. Since Step 5.22 (end of Phase 1, 01 Part B item 10)
 * the test FAILS on any finding that is not in the owner-approved allow-list
 * ({@code src/test/resources/architecture/allow-list.txt}); it still writes every finding to
 * {@code target/architecture/violations.txt}.
 * <p>
 * Approximations (bytecode-level, so deliberately conservative):
 * <ul>
 *   <li>(a) "calls TenantAccess" means the method itself, or a method of its own class that it
 *       calls (one hop), calls any {@link TenantAccess} method; or it calls an
 *       {@link #SCOPED_HELPERS allow-listed} helper.</li>
 *   <li>(b) is checked on every service method, not only controller-reachable ones, and
 *       "followed by access.record" means "the same method also calls a TenantAccess record
 *       check" ({@link #RECORD_CHECKS}); it does not prove the check is on the same id.</li>
 *   <li>(a) skips interface and abstract declarations; their implementations are checked.</li>
 * </ul>
 */
class ArchitectureReportTest {

    /**
     * Registered scoped helpers: classes whose public methods always enforce scope through TenantAccess.
     * They count as a TenantAccess call for rule (a) and as a record check for rule (b).
     * Module 04/05: LeadSettingsWriteScope / ProductWriteScope.check = TenantAccess.company() + the F-12 company-wide rule.
     */
    static final Set<String> SCOPED_HELPERS = Set.of("com.leads.service.LeadSettingsWriteScope", "com.leads.service.ProductWriteScope", "com.leads.service.DeliverySettingsWriteScope");

    /**
     * TenantAccess methods that check one record's scope. company()/branch()/scope() are the
     * record checks for Company and Branch, so a findById next to them counts as checked.
     */
    private static final Set<String> RECORD_CHECKS = Set.of("record", "company", "branch", "scope", "companyWide",
            "organization", "organizationAdmin");

    /** Types whose rows belong to a tenant: holding one of these as a field makes an entity a tenant entity. */
    private static final Set<Class<?>> TENANT_ANCHORS = Set.of(Organization.class, Company.class, Branch.class,
            Lead.class, Customer.class, WhatsAppConversation.class, WhatsAppMessage.class, Broadcast.class);

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.leads");

    // (a) ------------------------------------------------------------------------------------
    static final ArchRule SCOPED_ID_PARAMETERS = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("com.leads.service..")
            .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Service")
            .or().areDeclaredInClassesThat().haveSimpleNameEndingWith("ServiceImpl")
            .and().arePublic()
            .should(new ArchCondition<JavaMethod>("call TenantAccess when taking an entity id, companyId or branchId") {
                @Override public void check(JavaMethod m, ConditionEvents events) {
                    if (!m.getOwner().getPackageName().startsWith("com.leads.service")) return;
                    if (m.getOwner().isEquivalentTo(TenantAccess.class) || m.getModifiers().contains(JavaModifier.SYNTHETIC)) return;
                    // Interface/abstract declarations have no body; their implementations are checked.
                    if (m.getModifiers().contains(JavaModifier.ABSTRACT) || m.getOwner().isInterface()) return;
                    List<String> ids = scopedIdParameters(m);
                    if (ids.isEmpty() || callsTenantAccess(m, 1)) return;
                    events.add(SimpleConditionEvent.violated(m, "(a) " + m.getFullName() + " takes " + ids + " but never calls TenantAccess"));
                }
            })
            .as("(a) public service methods taking an entity id, companyId or branchId call TenantAccess")
            .allowEmptyShould(true);

    // (b) ------------------------------------------------------------------------------------
    static final ArchRule FIND_BY_ID_NEEDS_RECORD = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("com.leads.service..")
            .should(new ArchCondition<JavaMethod>("not call findById on a tenant entity without TenantAccess.record") {
                @Override public void check(JavaMethod m, ConditionEvents events) {
                    if (m.getOwner().isEquivalentTo(TenantAccess.class)) return;
                    boolean record = m.getMethodCallsFromSelf().stream().anyMatch(c ->
                            c.getTargetOwner().isEquivalentTo(TenantAccess.class) && RECORD_CHECKS.contains(c.getName())
                            || SCOPED_HELPERS.contains(c.getTargetOwner().getName()));
                    for (JavaMethodCall call : m.getMethodCallsFromSelf()) {
                        if (!call.getName().equals("findById") || !call.getTargetOwner().isAssignableTo(Repository.class)) continue;
                        Class<?> entity = repositoryEntity(call.getTargetOwner());
                        if (entity == null || !isTenantEntity(entity) || record) continue;
                        events.add(SimpleConditionEvent.violated(m, "(b) " + m.getFullName() + " calls "
                                + call.getTargetOwner().getSimpleName() + ".findById (" + entity.getSimpleName()
                                + ") without TenantAccess.record, line " + call.getLineNumber()));
                    }
                }
            })
            .as("(b) no findById on a tenant entity without TenantAccess.record (F-1/F-10/F-14 pattern)")
            .allowEmptyShould(true);

    // (c) ------------------------------------------------------------------------------------
    static final ArchRule CONTROLLERS_WITHOUT_REPOSITORIES = noClasses()
            .that().resideInAPackage("com.leads.controller..")
            .should().dependOnClassesThat().resideInAPackage("com.leads.repository..")
            .as("(c) controllers do not use repositories directly")
            .allowEmptyShould(true);

    // (d) D15 / 01 Part B item 11 ---------------------------------------------------------------
    /**
     * Calendar decisions must use CompanyClock. Bytecode cannot tell a timestamp from a calendar
     * decision, so this lists every LocalDate.now(), LocalDateTime.now() and atStartOfDay() in services
     * and controllers; each module clears or allow-lists its own sites (D15 call-site table).
     */
    static final ArchRule SERVER_DATE_CALLS = methods()
            .that().areDeclaredInClassesThat().resideInAnyPackage("com.leads.service..", "com.leads.controller..")
            .should(new ArchCondition<JavaMethod>("not use the server date for calendar decisions (use CompanyClock)") {
                @Override public void check(JavaMethod m, ConditionEvents events) {
                    if (m.getOwner().getSimpleName().equals("CompanyClock")) return;
                    for (JavaMethodCall c : m.getMethodCallsFromSelf()) {
                        String owner = c.getTargetOwner().getName();
                        boolean now = c.getName().equals("now") && (owner.equals("java.time.LocalDate") || owner.equals("java.time.LocalDateTime"));
                        boolean startOfDay = c.getName().equals("atStartOfDay") && owner.equals("java.time.LocalDate");
                        if (now || startOfDay) events.add(SimpleConditionEvent.violated(m, "(d) " + m.getFullName() + " calls "
                                + c.getTargetOwner().getSimpleName() + "." + c.getName() + "(), line " + c.getLineNumber()));
                    }
                }
            })
            .as("(d) services and controllers use CompanyClock, not LocalDate.now()/LocalDateTime.now()/atStartOfDay()")
            .allowEmptyShould(true);

    /**
     * Step 5.22 (01 Part B item 10): FAIL MODE. Every finding of rules (a)-(d) must be fixed or listed in
     * {@code src/test/resources/architecture/allow-list.txt} with an owner-approved reason code. The test also
     * fails on a stale entry (one that no longer matches a finding), so the list cannot outlive the code.
     * {@code target/architecture/violations.txt} still lists every finding, allow-listed or not.
     */
    @Test
    void noViolationsOutsideTheAllowList() throws Exception {
        assertFalse(CLASSES.isEmpty(), "no classes imported");
        List<String> out = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (ArchRule rule : List.of(SCOPED_ID_PARAMETERS, FIND_BY_ID_NEEDS_RECORD, CONTROLLERS_WITHOUT_REPOSITORIES, SERVER_DATE_CALLS)) {
            List<String> lines = report(rule);
            all.addAll(lines);
            out.add("== " + rule.getDescription() + ": " + lines.size() + " violation(s)");
            out.addAll(lines);
            out.add("");
        }
        Map<String, String> allowed = allowList();
        List<String> notAllowed = all.stream().filter(v -> !allowed.containsKey(key(v))).toList();
        Set<String> used = new java.util.HashSet<>();
        all.forEach(v -> used.add(key(v)));
        List<String> stale = allowed.keySet().stream().filter(k -> !used.contains(k)).sorted().toList();
        out.add(0, "ArchUnit FAIL mode (Step 5.22). Findings: " + all.size() + ", allow-listed: " + (all.size() - notAllowed.size())
                + ", not allowed: " + notAllowed.size() + ", stale allow-list entries: " + stale.size());
        Path dir = Path.of("target", "architecture");
        Files.createDirectories(dir);
        Files.write(dir.resolve("violations.txt"), out);
        out.stream().filter(l -> l.startsWith("==") || l.startsWith("ArchUnit")).forEach(System.out::println);
        assertTrue(notAllowed.isEmpty(), "Fix these or add them to architecture/allow-list.txt with an approved reason:\n" + String.join("\n", notAllowed));
        assertTrue(stale.isEmpty(), "Remove these allow-list entries; they no longer match a finding:\n" + String.join("\n", stale));
    }

    /** Evaluates one rule and returns its findings, sorted. */
    private static List<String> report(ArchRule rule) {
        EvaluationResult result = rule.evaluate(CLASSES);
        List<String> lines = new ArrayList<>(result.getFailureReport().getDetails());
        Collections.sort(lines);
        return lines;
    }

    /** Owner-approved reason codes for allow-list entries (Step 5.22). */
    enum Reason {
        /** A state or audit timestamp (setXxxAt(now)), an expiry or a duration: stored as server wall-clock, not a calendar decision. */
        TIMESTAMP,
        /** atStartOfDay() on a company-local date that is then converted with CompanyClock.toServer. */
        COMPANY_DAY_HELPER,
        /** A scheduler, executor or webhook path: the id never comes from a user; runs under TenantAccess.inCompany where it acts for a company. */
        SYSTEM_PATH,
        /** An internal helper: every caller has already scope-checked the record or company the id comes from. */
        CHECKED_CALLER,
        /** Checked another way (named in the note). */
        CHECKED_OTHERWISE
    }

    /** A finding without its line number (lines drift; the method and the call identify it). */
    static String key(String violation) {
        return violation.replaceAll(", line \\d+$", "").trim();
    }

    /** {@code key || REASON || note}, one per line; # comments and blank lines ignored. */
    static Map<String, String> allowList() throws Exception {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        try (var in = ArchitectureReportTest.class.getResourceAsStream("/architecture/allow-list.txt")) {
            if (in == null) return entries;
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(" \\|\\| ");
                if (parts.length != 3 || parts[2].isBlank()) throw new IllegalStateException("Malformed allow-list line (key || REASON || note): " + line);
                Reason.valueOf(parts[1].trim()); // unknown reason codes fail the test
                if (entries.put(key(parts[0]), parts[1].trim()) != null) throw new IllegalStateException("Duplicate allow-list entry: " + parts[0]);
            }
        }
        return entries;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static List<String> scopedIdParameters(JavaMethod m) {
        List<String> names = new ArrayList<>();
        try {
            Method reflected = (Method) m.reflect();
            for (Parameter p : reflected.getParameters()) {
                boolean longType = p.getType() == Long.class || p.getType() == long.class;
                // Compiled with -parameters (spring-boot-starter-parent), so names are real.
                if (longType && (p.getName().equals("id") || p.getName().endsWith("Id"))) names.add(p.getName());
            }
        } catch (RuntimeException | LinkageError e) {
            return names;
        }
        return names;
    }

    private static boolean callsTenantAccess(JavaMethod m, int hops) {
        for (JavaMethodCall c : m.getMethodCallsFromSelf()) {
            JavaClass owner = c.getTargetOwner();
            if (owner.isEquivalentTo(TenantAccess.class) || SCOPED_HELPERS.contains(owner.getName())) return true;
        }
        if (hops == 0) return false;
        for (JavaMethodCall c : m.getMethodCallsFromSelf()) {
            if (!c.getTargetOwner().equals(m.getOwner())) continue;
            for (JavaMethod target : c.getTarget().resolveMember().stream().toList()) {
                if (!target.equals(m) && callsTenantAccess(target, hops - 1)) return true;
            }
        }
        return false;
    }

    private static Class<?> repositoryEntity(JavaClass repository) {
        try {
            return entityOf(repository.reflect());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Class<?> entityOf(Class<?> type) {
        for (Type t : type.getGenericInterfaces()) {
            if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw
                    && Repository.class.isAssignableFrom(raw) && pt.getActualTypeArguments()[0] instanceof Class<?> e) return e;
            if (t instanceof Class<?> c && Repository.class.isAssignableFrom(c)) {
                Class<?> e = entityOf(c);
                if (e != null) return e;
            }
        }
        return null;
    }

    private static boolean isTenantEntity(Class<?> entity) {
        if (TENANT_ANCHORS.contains(entity) && entity != Organization.class) return true;
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) if (TENANT_ANCHORS.contains(f.getType())) return true;
        }
        return false;
    }
}
