package edu.softwarelab.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import edu.softwarelab.starter.StarterApplication;
import edu.softwarelab.starter.DatabaseConnectionCheck;
import edu.softwarelab.starter.messaging.S4ResultConsumer;
import edu.softwarelab.starter.messaging.S5ResultConsumer;
import edu.softwarelab.starter.messaging.OutboxDispatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import edu.softwarelab.starter.domain.Transfer;
import edu.softwarelab.starter.domain.TransferStatus;
import edu.softwarelab.starter.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** In-process main Spring Boot application + actual controller/advice/filter; service is a deterministic mock.
 * Response status, media type and entire body are checked against openapi/payment.yaml.
 * No external network, PostgreSQL or Kafka is used by these API-layer tests.
 */
@SpringBootTest(classes = StarterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
class TransferApiContractTest {
    @MockitoBean TransferService service;
    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean DatabaseConnectionCheck databaseConnectionCheck;
    @MockitoBean S4ResultConsumer s4ResultConsumer;
    @MockitoBean S5ResultConsumer s5ResultConsumer;
    @MockitoBean OutboxDispatcher outboxDispatcher;

    private static final String BODY = """
        {"source_account_number":"001200456789", "dest_account_number":"001200987654",
         "amount":2500000, "currency":"IRR", "description":"Test transfer"}
        """;
    private static final UUID TRANSFER_ID = UUID.fromString("1f28e913-df36-4102-af49-f76d6141b707");
    private static final UUID CORRELATION_ID = UUID.fromString("67ec2289-c3b7-4dc0-a911-c67d66c351f3");
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode contract;
    @Autowired MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        reset(service);
        try (InputStream input = Files.newInputStream(Path.of("openapi", "payment.yaml"))) {
            contract = new ObjectMapper(new YAMLFactory()).readTree(input);
        }
        assertTrue(contract.path("openapi").asText().startsWith("3.1."), "Expected OpenAPI 3.1");
    }

    private Transfer successfulTransfer() {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-11-08T15:30:00+03:30");
        return new Transfer(TRANSFER_ID, "001200456789", "001200987654", null,
                null, null, 2500000L, "IRR", TransferStatus.RISK_CHECKING, true,
                "Test transfer", "api-test-key", CORRELATION_ID, null, null, null, null,
                timestamp, timestamp, 0L);
    }

    /** Resolve a local OpenAPI ref; this is response-level resolution, not a mocked schema. */
    private JsonNode dereference(JsonNode node) {
        for (int i = 0; node.has("$ref") && i < 10; i++) {
            String ref = node.path("$ref").asText();
            assertTrue(ref.startsWith("#/"), "Only local OpenAPI refs supported: " + ref);
            node = contract.at(ref.substring(1));
            assertFalse(node.isMissingNode(), "Missing response ref " + ref);
        }
        return node;
    }

    private JsonSchema responseSchema(MvcResult result, int expectedStatus, String expectedType) {
        int actualStatus = result.getResponse().getStatus();
        assertEquals(expectedStatus, actualStatus, "HTTP status");
        String actualContentType = result.getResponse().getContentType();
        assertNotNull(actualContentType, "Missing Content-Type");
        MediaType type = MediaType.parseMediaType(actualContentType);
        assertEquals(MediaType.parseMediaType(expectedType).getType(), type.getType());
        assertEquals(MediaType.parseMediaType(expectedType).getSubtype(), type.getSubtype());

        // Locate response by operation, actual status and actual media type in the actual OAS file.
        JsonNode response = contract.path("paths").path("/transfers").path("post")
                .path("responses").path(String.valueOf(actualStatus));
        assertFalse(response.isMissingNode(), "Status is not declared in OpenAPI");
        response = dereference(response);
        JsonNode schema = response.path("content").path(expectedType).path("schema");
        assertFalse(schema.isMissingNode(), "Response media type/schema missing in OpenAPI");
        ObjectNode root = json.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.set("components", contract.path("components").deepCopy());
        root.putArray("allOf").add(schema.deepCopy());
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root, config);
    }

    @Test
    void createTransfer_successMatchesOpenApi() throws Exception {
        when(service.createTransfer(any(), eq("api-test-key"), any(UUID.class))).thenReturn(successfulTransfer());
        MvcResult result = mvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "api-test-key")
                .header("X-Correlation-ID", CORRELATION_ID.toString())
                .content(BODY)).andReturn();
        JsonSchema schema = responseSchema(result, 201, "application/json");
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        var errors = schema.validate(body);
        System.out.println("API SUCCESS status=" + result.getResponse().getStatus()
                + " contentType=" + result.getResponse().getContentType() + " schemaErrors=" + errors);
        assertTrue(errors.isEmpty(), "OpenAPI Transfer response mismatches: " + errors + " body=" + body);
        assertEquals(TRANSFER_ID.toString(), body.path("id").asText());
        assertEquals("IRR", body.path("currency").asText());
        assertEquals("2026-11-08T12:00:00Z", body.path("created_at").asText());
        assertNotNull(result.getResponse().getHeader("Location"));
        assertTrue(result.getResponse().getHeader("Location").endsWith("/" + TRANSFER_ID));
        verify(service).createTransfer(any(), eq("api-test-key"), eq(CORRELATION_ID));
        ObjectNode invalid = (ObjectNode) body.deepCopy();
        invalid.remove("id");
        assertFalse(schema.validate(invalid).isEmpty(), "Negative control: missing required id must fail");
    }

    @Test
    void createTransfer_missingIdempotencyKeyMatchesOpenApiProblem() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-ID", CORRELATION_ID.toString())
                .content(BODY)).andReturn();
        JsonSchema schema = responseSchema(result, 400, "application/problem+json");
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        var errors = schema.validate(body);
        System.out.println("API ERROR status=" + result.getResponse().getStatus()
                + " contentType=" + result.getResponse().getContentType() + " schemaErrors=" + errors);
        assertTrue(errors.isEmpty(), "OpenAPI Problem response mismatches: " + errors + " body=" + body);
        assertEquals(400, body.path("status").asInt());
        assertEquals("VALIDATION_ERROR", body.path("code").asText());
        assertEquals(CORRELATION_ID.toString(), body.path("correlation_id").asText());
        verifyNoInteractions(service);
        ObjectNode invalid = body.deepCopy();
        invalid.remove("code");
        assertFalse(schema.validate(invalid).isEmpty(), "Negative control: missing required code must fail");
    }
}
