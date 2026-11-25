package com.leads.it.matrix;

import com.leads.it.AbstractIntegrationTest;
import com.leads.it.matrix.EndpointSpec.Probe;
import com.leads.it.matrix.EndpointSpec.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The D3 endpoint isolation matrix (01 Part A item 6). One invocation per row; rows come from
 * the module *Rows classes via {@link Matrix}. A denial row also asserts that nothing was
 * sent to Meta and that the target record is unchanged.
 * <p>
 * Results: {@code target/isolation-matrix/results.txt}.
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class IsolationMatrixIT extends AbstractIntegrationTest {

    private static final List<String> RESULTS = Collections.synchronizedList(new ArrayList<>());

    @Autowired MockMvc mvc;
    @Autowired com.leads.flags.FeatureFlags flags;

    @org.junit.jupiter.api.BeforeAll
    void matrixFlagsOn() { Matrix.flagsOn(flags, fixture); }

    static Stream<Row> rows() { return Matrix.rows().stream(); }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void row(Row row) throws Exception {
        Probe probe = new Probe();
        MockHttpServletRequestBuilder request = row.request().build(fixture, row.target(), probe);
        if (row.persona() != null) request.header("Authorization", "Bearer " + fixture.token(row.org(), row.persona()));
        String before = probe.state() == null ? null : probe.state().get();
        Mockito.clearInvocations(graphApi);

        MvcResult result = mvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        String detail = row + " -> got " + status + " " + (body.length() > 300 ? body.substring(0, 300) + "..." : body);

        if (row.knownLeak() != null) {
            RESULTS.add("KNOWN-LEAK " + detail + "  [" + row.knownLeak() + "]");
            assertFalse(row.expected().matches(status),
                    "Known leak is fixed - remove its knownLeak(...) marker so the row asserts the real expectation: " + detail);
            return;
        }
        boolean ok = row.expected().matches(status);
        RESULTS.add((ok ? "PASS " : "FAIL ") + detail);
        assertTrue(ok, detail);

        if (row.expected() != Outcome.ALLOWED) {
            Mockito.verifyNoInteractions(graphApi);
            if (before != null) assertEquals(before, probe.state().get(), "denied request changed the record: " + row);
        }
    }

    @AfterAll
    void matrixFlagsOff() { Matrix.flagsOff(flags, fixture); }

    @AfterAll
    static void writeReport() throws Exception {
        Path dir = Path.of("target", "isolation-matrix");
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>(RESULTS);
        long fail = lines.stream().filter(l -> l.startsWith("FAIL")).count();
        long leak = lines.stream().filter(l -> l.startsWith("KNOWN-LEAK")).count();
        lines.add(0, "rows=" + lines.size() + " fail=" + fail + " knownLeak=" + leak);
        Files.write(dir.resolve("results.txt"), lines);
    }
}
