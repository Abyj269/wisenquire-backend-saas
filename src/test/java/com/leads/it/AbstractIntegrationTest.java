package com.leads.it;

import com.leads.it.fixture.TenantFixture;
import com.leads.service.WhatsAppGraphApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for every *IT (01 Part A item 6, D3).
 * <ul>
 *   <li>One MySQL 8 container for the whole run (started once, reused by every IT class;
 *       Ryuk removes it when the JVM exits). Flyway applies V1..Vn exactly as in prod.</li>
 *   <li>One Spring context for all ITs: keep the annotations here identical in subclasses,
 *       or the context cache splits and the run slows down.</li>
 *   <li>Requests go through MockMvc and the real security filter chain (JWT filter,
 *       {@code @PreAuthorize}, GlobalExceptionHandler). MockMvc does not apply the servlet
 *       context path, so paths are written without {@code /api/v1}.</li>
 *   <li>The Meta Graph client is a mock: no test ever reaches graph.facebook.com.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Import(TenantFixture.class)
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("wisenquire_it")
            .withUsername("wisenquire")
            .withPassword("it-only-pw")
            // Server settings must match prod (docs/DATABASE_ARCHITECTURE.md).
            .withCommand("--lower-case-table-names=1",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @MockBean
    protected WhatsAppGraphApiClient graphApi;

    /** Module 18: the AI provider calls (chat / transcription), metered; no real provider is ever called. */
    @MockBean
    protected com.leads.service.AiMeteredCalls aiCalls;

    /** Module 19: the Meta / Google Ads clients; no real ad platform is ever called. */
    @MockBean
    protected com.leads.service.AdsProviderClientFactory adsClients;

    @Autowired
    protected TenantFixture fixture;
}
