package com.leads.it.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.it.fixture.TenantFixture;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.*;
import java.util.function.Supplier;

/**
 * One endpoint's matrix definition, owned by its module (see the *Rows classes).
 * <p>
 * {@link Request} builds the call aimed at a target {@link Slot}: the IDs in the path, query
 * and body all come from that slot, so an X-ORG row against B1a sends org-B IDs everywhere.
 * Rows that write must use the fixture's {@code fresh*} records, never the shared ones.
 */
public final class EndpointSpec {

    /** What the endpoint is scoped by; decides which X-* rows apply. */
    public enum Scope {
        /** A record or a branchId: X-ORG, X-COMPANY, X-BRANCH. */
        BRANCH,
        /** Only a companyId (no branch parameter): X-ORG, X-COMPANY. */
        COMPANY,
        /** Needs company-wide scope: branch-level personas are denied even at home. */
        COMPANY_WIDE
    }

    @FunctionalInterface
    public interface Request {
        MockHttpServletRequestBuilder build(TenantFixture f, Slot target, Probe probe);
    }

    /** Lets a request register a before/after state check: a denied row must leave it unchanged. */
    public static final class Probe {
        private Supplier<String> state;
        public void watch(Supplier<String> state) { this.state = state; }
        public Supplier<String> state() { return state; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public final String key;
    public final Scope scope;
    public final Request request;
    final Set<Persona> personas = EnumSet.allOf(Persona.class);
    final List<String> notes = new ArrayList<>();
    String noAllowedReason;
    final List<Row> extra = new ArrayList<>();
    final Map<String, String> knownLeaks = new HashMap<>();

    private EndpointSpec(String key, Scope scope, Request request) {
        this.key = key;
        this.scope = scope;
        this.request = request;
    }

    public static EndpointSpec of(String key, Scope scope, Request request) {
        return new EndpointSpec(key, scope, request);
    }

    /** Only these personas can pass {@code @PreAuthorize} at all; rows are generated for them only. */
    public EndpointSpec onlyFor(Persona... allowed) {
        personas.clear();
        personas.addAll(List.of(allowed));
        return this;
    }

    /** No ALLOWED control row, with the reason (e.g. the fixture cannot make the call succeed). */
    public EndpointSpec noAllowedRow(String reason) {
        this.noAllowedReason = reason;
        return this;
    }

    /** An extra hand-written row (e.g. the F-1 company-wide broadcast rows). */
    public EndpointSpec row(RowType type, char org, Persona persona, Slot target, Outcome expected, Request request) {
        extra.add(new Row(this, type, org, persona, target, expected, request, null));
        return this;
    }

    /**
     * A real leak this matrix found and the owner has not fixed yet. The row then asserts the
     * leak is STILL there (so the fix turns it red and forces this marker's removal) and is
     * listed in the coverage report. {@code rowId} is {@link Row#id()}.
     */
    public EndpointSpec knownLeak(String rowId, String reason) {
        knownLeaks.put(rowId, reason);
        return this;
    }

    // ---- request helpers -----------------------------------------------------------------

    public static String json(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) body.put((String) keyValues[i], keyValues[i + 1]);
        try { return JSON.writeValueAsString(body); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** One generated or hand-written matrix row. */
    public record Row(EndpointSpec spec, RowType type, char org, Persona persona, Slot target,
                      Outcome expected, Request request, String knownLeak) {
        public String id() {
            return type + " " + (persona == null ? "anonymous" : org + ":" + persona) + " -> " + target;
        }
        @Override public String toString() { return spec.key + " | " + id() + " | expect " + expected.describe(); }
    }
}
