package com.leads.it.matrix;

import com.leads.it.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "A new endpoint without a matrix row fails CI" (01 Part A item 6).
 * <p>
 * Every endpoint Spring MVC registers must be in exactly one of:
 * <ul>
 *   <li>the matrix ({@link Matrix#specs()});</li>
 *   <li>{@code isolation-matrix/pending-endpoints.txt}: the backlog, each line tagged with the
 *       module that owns its rows. It only ever shrinks: each module's Phase 1 moves its lines
 *       into a *Rows class;</li>
 *   <li>{@code isolation-matrix/exempt-endpoints.txt}: public, non-tenant endpoints, each with a reason.</li>
 * </ul>
 * Stale entries (an endpoint that no longer exists, or that is listed twice) fail too.
 * Report: {@code target/isolation-matrix/coverage.txt}.
 */
class EndpointCoverageIT extends AbstractIntegrationTest {

    /**
     * The backlog may only shrink: a new endpoint cannot be parked in pending-endpoints.txt.
     * 141 at Step 3.2 (2026-11-24); 139 after module 02 P1 (2026-11-25). Lower it whenever a module
     * moves lines into the matrix.
     */
    static final int PENDING_CEILING = 43;

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointHasRowsOrIsListed() throws Exception {
        Map<String, String> discovered = EndpointCatalog.discoverWithControllers(mappings);
        Set<String> covered = Matrix.coveredKeys();
        Map<String, String> pending = read("isolation-matrix/pending-endpoints.txt");
        Map<String, String> exempt = read("isolation-matrix/exempt-endpoints.txt");

        List<String> problems = new ArrayList<>();
        for (String key : discovered.keySet()) {
            int places = (covered.contains(key) ? 1 : 0) + (pending.containsKey(key) ? 1 : 0) + (exempt.containsKey(key) ? 1 : 0);
            if (places == 0) problems.add("NO MATRIX ROW: " + key + " (" + discovered.get(key) + ") - add rows to its module's *Rows class");
            if (places > 1) problems.add("LISTED TWICE: " + key + " - remove it from pending/exempt once it has rows");
        }
        for (String key : covered) if (!discovered.containsKey(key)) problems.add("STALE MATRIX SPEC (no such endpoint): " + key);
        for (String key : pending.keySet()) if (!discovered.containsKey(key)) problems.add("STALE pending-endpoints.txt line: " + key);
        for (String key : exempt.keySet()) if (!discovered.containsKey(key)) problems.add("STALE exempt-endpoints.txt line: " + key);
        for (Map.Entry<String, String> e : exempt.entrySet()) if (e.getValue().isBlank()) problems.add("EXEMPT WITHOUT REASON: " + e.getKey());
        for (Map.Entry<String, String> e : pending.entrySet()) if (!e.getValue().matches("module \\d\\d.*")) problems.add("PENDING WITHOUT OWNING MODULE: " + e.getKey());

        if (pending.size() > PENDING_CEILING) {
            problems.add("pending-endpoints.txt grew to " + pending.size() + " (ceiling " + PENDING_CEILING
                    + "): a new endpoint needs matrix rows, not a pending line");
        }

        writeReport(discovered, covered, pending, exempt, problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static void writeReport(Map<String, String> discovered, Set<String> covered, Map<String, String> pending,
                                    Map<String, String> exempt, List<String> problems) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("endpoints=" + discovered.size() + " covered=" + covered.size() + " pending=" + pending.size() + " exempt=" + exempt.size());
        Map<String, int[]> byController = new TreeMap<>();
        discovered.forEach((k, c) -> {
            int[] n = byController.computeIfAbsent(c, x -> new int[2]);
            n[0]++;
            if (covered.contains(k)) n[1]++;
        });
        out.add("");
        out.add("controller: covered/total");
        byController.forEach((c, n) -> out.add(String.format("  %-34s %3d/%-3d", c, n[1], n[0])));
        out.add("");
        out.add("problems=" + problems.size());
        out.addAll(problems);
        Path dir = Path.of("target", "isolation-matrix");
        Files.createDirectories(dir);
        Files.write(dir.resolve("coverage.txt"), out);
        // Ready-to-paste lines for anything not yet listed anywhere.
        List<String> missing = new ArrayList<>();
        discovered.forEach((k, c) -> { if (!covered.contains(k) && !pending.containsKey(k) && !exempt.containsKey(k)) missing.add(k + "  # " + c); });
        Files.write(dir.resolve("unlisted.txt"), missing);
    }

    /** {@code KEY  # comment} lines; blank lines and lines starting with # are ignored. */
    private static Map<String, String> read(String resource) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        var in = EndpointCoverageIT.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) throw new IllegalStateException("missing test resource " + resource);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int hash = line.indexOf('#');
                String key = (hash < 0 ? line : line.substring(0, hash)).strip();
                String note = hash < 0 ? "" : line.substring(hash + 1).strip();
                if (out.put(key, note) != null) throw new IllegalStateException(resource + ": duplicate line " + key);
            }
        }
        return out;
    }
}
