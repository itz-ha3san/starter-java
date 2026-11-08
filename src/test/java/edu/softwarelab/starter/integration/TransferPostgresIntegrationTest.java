package edu.softwarelab.starter.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.softwarelab.starter.domain.OutboxRepository;
import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferRepository;
import edu.softwarelab.starter.domain.TransferStatus;
import edu.softwarelab.starter.service.TransferService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL integration tests. Point S3_TEST_DB_URL to a dedicated test DB.
 * Each test uses and drops only a randomly named schema. No Kafka broker is needed.
 */
class TransferPostgresIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    static class TxConfiguration { }

    private String schema;
    private DriverManagerDataSource adminDataSource;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        String url = System.getenv("S3_TEST_DB_URL");
        String user = System.getenv("S3_TEST_DB_USER");
        String password = System.getenv("S3_TEST_DB_PASSWORD");
        if (url == null || url.isBlank() || user == null || user.isBlank() || password == null) {
            throw new IllegalStateException("Set S3_TEST_DB_URL, S3_TEST_DB_USER and S3_TEST_DB_PASSWORD for a dedicated PostgreSQL test database");
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("S3_TEST_DB_URL must point to PostgreSQL");
        }
        schema = "s3test_" + UUID.randomUUID().toString().replace("-", "");
        adminDataSource = new DriverManagerDataSource(url, user, password);
        DriverManagerDataSource testDataSource = new DriverManagerDataSource(url, user, password);
        Properties props = new Properties();
        props.setProperty("currentSchema", schema);
        testDataSource.setConnectionProperties(props);
        Flyway.configure().dataSource(testDataSource).schemas(schema).defaultSchema(schema)
                .createSchemas(true).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(testDataSource);

        context = new AnnotationConfigApplicationContext();
        context.register(TxConfiguration.class);
        context.registerBean(DataSource.class, () -> testDataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(testDataSource));
        context.registerBean("normalService", TransferService.class, () -> service(new OutboxRepository(jdbc)));
        context.registerBean("failureService", TransferService.class, () -> service(new OutboxRepository(jdbc) {
            @Override
            public void enqueue(UUID id, UUID aggregateId, String topic, String key, String payload) {
                super.enqueue(id, aggregateId, topic, key, payload);
                throw new IllegalStateException("injected failure after outbox insert");
            }
        }));
        context.refresh();
    }

    private TransferService service(OutboxRepository outbox) {
        return new TransferService(new TransferRepository(jdbc), outbox,
                new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (adminDataSource != null && schema != null) {
            new JdbcTemplate(adminDataSource).execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    private TransferService.CreateTransferRequest request() {
        return new TransferService.CreateTransferRequest(
                "001200456789", "001200987654", null, 2_500_000L, "IRR", "integration");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void createsAndReplaysOneTransferWithOneOutboxMessage() {
        TransferService service = context.getBean("normalService", TransferService.class);
        Transfer first = service.createTransfer(request(), "pg-idem-1", UUID.randomUUID());
        Transfer replay = service.createTransfer(request(), "pg-idem-1", UUID.randomUUID());
        assertEquals(first.id(), replay.id());
        assertEquals(TransferStatus.RISK_CHECKING, first.status());
        assertEquals(1, count("transfers"));
        assertEquals(2, count("transfer_events"));
        assertEquals(1, count("outbox_messages"));
        assertEquals(1, count("idempotency_responses"));
    }

    @Test
    void rollsBackTransferEventsAndOutboxWhenFailureOccursAfterOutboxInsert() {
        TransferService service = context.getBean("failureService", TransferService.class);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createTransfer(request(), "pg-rollback-1", UUID.randomUUID()));
        assertEquals("injected failure after outbox insert", failure.getMessage());
        assertEquals(0, count("transfers"));
        assertEquals(0, count("transfer_events"));
        assertEquals(0, count("outbox_messages"));
        assertEquals(0, count("idempotency_responses"));
    }
}
