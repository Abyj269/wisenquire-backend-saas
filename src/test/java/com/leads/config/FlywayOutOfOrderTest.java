package com.leads.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Flyway version blocks (00-INDEX) are reserved per module, so migrations are not applied in number
 * order (V65, V66 and V110 run before a later V57, V67 or V70). Without out-of-order, Flyway would
 * refuse to start once a lower version appears. Every profile that runs Flyway must keep it on.
 */
class FlywayOutOfOrderTest {

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(strings = {"application-prod.yml", "application-windows-dev.yml", "application-it.yml"})
    void everyFlywayProfileAllowsOutOfOrderMigrations(String file) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(file)) {
            assertNotNull(in, file);
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> flyway = (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("flyway");
            assertEquals(Boolean.TRUE, flyway.get("out-of-order"), file + ": spring.flyway.out-of-order");
        }
    }
}
