package com.leads.it.matrix;

import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.it.matrix.EndpointSpec.Row;
import com.leads.model.enums.RoleLevel;

import java.util.*;

/**
 * Expands every module's {@link EndpointSpec}s into D3 rows. Personas act from org A; targets:
 * <pre>
 *   ALLOWED     each persona   -> A1a (its own branch)       2xx   (DENIED for branch-level on COMPANY_WIDE)
 *   ANON        no token       -> A1a                       401
 *   X_ORG       each persona   -> B1a                       DENIED
 *   X_COMPANY   COMPANY_MGR    -> A2a                       DENIED
 *   X_BRANCH    BRANCH_MGR     -> A1b   (scope BRANCH only) DENIED
 * </pre>
 * plus each spec's hand-written rows. Denial rows run before ALLOWED rows, so an async side
 * effect of an allowed call (e.g. a broadcast send) can never be blamed on a denial row.
 */
public final class Matrix {

    /** Modules with matrix rows. A module joins by adding its *Rows class here. */
    public static List<EndpointSpec> specs() {
        List<EndpointSpec> all = new ArrayList<>();
        all.addAll(BroadcastRows.specs());
        all.addAll(LeadRows.specs());
        all.addAll(CustomerRows.specs());
        all.addAll(WhatsAppRows.specs());
        all.addAll(ReportsRows.specs());
        all.addAll(MeRows.specs());
        all.addAll(AuthRows.specs());
        all.addAll(TenantRows.specs());
        return all;
    }

    public static List<Row> rows() {
        List<Row> denials = new ArrayList<>(), allowed = new ArrayList<>();
        for (EndpointSpec spec : specs()) {
            List<Row> rows = new ArrayList<>();
            rows.add(new Row(spec, RowType.ANON, 'A', null, Slot.A1a, Outcome.UNAUTHENTICATED, spec.request, null));
            if (spec.scope == EndpointSpec.Scope.PLATFORM) {
                for (Persona p : Persona.values()) rows.add(new Row(spec, RowType.CFG_SCOPE, 'A', p, Slot.A1a, Outcome.DENIED, spec.request, null));
                rows.forEach(denials::add);
                continue;
            }
            if (spec.scope == EndpointSpec.Scope.SELF) {
                for (Persona p : spec.personas) rows.add(new Row(spec, RowType.ALLOWED, 'A', p, Slot.A1a, Outcome.ALLOWED, spec.request, null));
                rows.forEach(r -> (r.expected() == Outcome.ALLOWED ? allowed : denials).add(r));
                continue;
            }
            for (Persona p : spec.personas) {
                boolean branchLevel = p.level == RoleLevel.BRANCH;
                if (spec.scope == EndpointSpec.Scope.COMPANY_WIDE && branchLevel) {
                    rows.add(new Row(spec, RowType.X_COMPANY_WIDE, 'A', p, Slot.A1a, Outcome.DENIED, spec.request, null));
                } else if (spec.noAllowedReason == null) {
                    rows.add(new Row(spec, RowType.ALLOWED, 'A', p, Slot.A1a, Outcome.ALLOWED, spec.request, null));
                }
                rows.add(new Row(spec, RowType.X_ORG, 'A', p, Slot.B1a, Outcome.DENIED, spec.request, null));
            }
            if (spec.personas.contains(Persona.COMPANY_MGR) && spec.scope != EndpointSpec.Scope.ORG) {
                rows.add(new Row(spec, RowType.X_COMPANY, 'A', Persona.COMPANY_MGR, Slot.A2a, Outcome.DENIED, spec.request, null));
            }
            if (spec.scope == EndpointSpec.Scope.BRANCH && spec.personas.contains(Persona.BRANCH_MGR)) {
                rows.add(new Row(spec, RowType.X_BRANCH, 'A', Persona.BRANCH_MGR, Slot.A1b, Outcome.DENIED, spec.request, null));
            }
            // STATE (01 Part B item 2): the org's ORG_ADMIN on its own branch. Writes are blocked;
            // reads still work. Skipped where the spec cannot produce a successful call at all.
            if (spec.noAllowedReason == null && spec.personas.contains(Persona.ORG_ADMIN)) {
                boolean write = !spec.key.startsWith("GET ");
                rows.add(new Row(spec, RowType.STATE, 'S', Persona.ORG_ADMIN, Slot.S1a, write ? Outcome.TENANT_SUSPENDED : Outcome.ALLOWED, spec.request, null));
                rows.add(new Row(spec, RowType.STATE, 'P', Persona.ORG_ADMIN, Slot.P1a, write ? Outcome.TENANT_PAST_DUE : Outcome.ALLOWED, spec.request, null));
            }
            rows.addAll(spec.extra);
            for (Row r : rows) {
                String leak = spec.knownLeaks.get(r.id());
                Row row = leak == null ? r : new Row(r.spec(), r.type(), r.org(), r.persona(), r.target(), r.expected(), r.request(), leak);
                (row.expected() == Outcome.ALLOWED ? allowed : denials).add(row);
            }
            Set<String> ids = new HashSet<>();
            rows.forEach(r -> ids.add(r.id()));
            for (String leakId : spec.knownLeaks.keySet()) {
                if (!ids.contains(leakId)) throw new IllegalStateException(spec.key + ": knownLeak for a row that does not exist: " + leakId);
            }
        }
        List<Row> ordered = new ArrayList<>(denials);
        ordered.addAll(allowed);
        return ordered;
    }

    /** Endpoint keys that have rows. */
    public static Set<String> coveredKeys() {
        Set<String> keys = new TreeSet<>();
        for (EndpointSpec s : specs()) {
            if (!keys.add(s.key)) throw new IllegalStateException("Duplicate matrix spec: " + s.key);
        }
        return keys;
    }

    private Matrix() { }
}
