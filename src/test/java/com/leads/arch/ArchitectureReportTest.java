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

/**
 * Plan D3-3 / 01 Part A item 7: ArchUnit rules a-c in REPORT MODE. This test never fails on a
 * violation; it prints the counts and writes {@code target/architecture/violations.txt}.
 * The rules switch to failing at the end of Phase 1 (01 Part B item 10), after modules 02-19
 * have cleared or allow-listed their violations: then replace {@link #report} with
 * {@code rule.check(classes)}.
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

    /** Registered scoped helpers for rule (a): classes whose public methods always enforce scope. Empty in Phase 0. */
    static final Set<String> SCOPED_HELPERS = Set.of();

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
                            c.getTargetOwner().isEquivalentTo(TenantAccess.class) && RECORD_CHECKS.contains(c.getName()));
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

    @Test
    void reportViolations() throws Exception {
        assertFalse(CLASSES.isEmpty(), "no classes imported");
        List<String> out = new ArrayList<>();
        int total = 0;
        for (ArchRule rule : List.of(SCOPED_ID_PARAMETERS, FIND_BY_ID_NEEDS_RECORD, CONTROLLERS_WITHOUT_REPOSITORIES)) {
            List<String> lines = report(rule);
            total += lines.size();
            out.add("== " + rule.getDescription() + ": " + lines.size() + " violation(s)");
            out.addAll(lines);
            out.add("");
        }
        out.add(0, "ArchUnit report mode (01 Part A item 7). Total violations: " + total);
        Path dir = Path.of("target", "architecture");
        Files.createDirectories(dir);
        Files.write(dir.resolve("violations.txt"), out);
        out.stream().filter(l -> l.startsWith("==") || l.startsWith("ArchUnit")).forEach(System.out::println);
    }

    /** Report mode: evaluate, never throw. */
    private static List<String> report(ArchRule rule) {
        EvaluationResult result = rule.evaluate(CLASSES);
        List<String> lines = new ArrayList<>(result.getFailureReport().getDetails());
        Collections.sort(lines);
        return lines;
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
