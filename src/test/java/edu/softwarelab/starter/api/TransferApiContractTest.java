package edu.softwarelab.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the S1 transfer-check API specification consumed by S3.
 * These tests intentionally validate the supplied OpenAPI document and its
 * request/response examples. They do not start the S3 Spring application and
 * do not claim to test a live S1 provider or an HTTP client: the current S3
 * application does not yet expose this S1 endpoint or contain the S1 client.
 */
class TransferApiContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode contract;
    private static JsonNode operation;

    @BeforeAll
    static void loadContract() throws Exception {
        Path contractPath = Path.of("openapi", "payment.yaml");
        assertTrue(Files.isRegularFile(contractPath),
                "OpenAPI contract not found at " + contractPath.toAbsolutePath());
        try (InputStream input = Files.newInputStream(contractPath)) {
            contract = new ObjectMapper(new YAMLFactory()).readTree(input);
        }
        assertEquals("3.1.0", contract.path("openapi").asText());
        operation = contract.path("paths").path("/api/v1/accounts/transfer-check").path("post");
        assertFalse(operation.isMissingNode(), "S1 transfer-check POST operation is missing");
    }

    @Test
    void declaresTheS1OperationAndItsAuthenticationContract() {
        assertEquals("checkTransferAccounts", operation.path("operationId").asText());
        assertEquals("Check the source and destination account of a transfer",
                operation.path("summary").asText());

        JsonNode security = operation.path("security");
        assertTrue(security.isArray() && security.size() == 1);
        assertTrue(security.get(0).has("bearerAuth"), "Bearer JWT authentication must be required");
        assertEquals("http", contract.at("/components/securitySchemes/bearerAuth/type").asText());
        assertEquals("bearer", contract.at("/components/securitySchemes/bearerAuth/scheme").asText());

        JsonNode parameters = operation.path("parameters");
        assertEquals(1, parameters.size(), "Only X-Correlation-ID is declared as an operation parameter");
        JsonNode correlation = resolve(parameters.get(0));
        assertEquals("X-Correlation-ID", correlation.path("name").asText());
        assertEquals("header", correlation.path("in").asText());
        assertFalse(correlation.path("required").asBoolean());
    }

    @Test
    void requestRequiresBothShebaFieldsAndRejectsExtraProperties() {
        JsonNode requestBody = operation.path("requestBody");
        assertTrue(requestBody.path("required").asBoolean());
        JsonNode requestSchema = requestBody.at("/content/application~1json/schema");
        JsonSchema schema = compile(requestSchema);

        JsonNode valid = readJson("""
                {
                  "source_sheba": "IR120990000000000000000001",
                  "destination_sheba": "IR330990000000000000000002"
                }
                """);
        assertTrue(schema.validate(valid).isEmpty(), "Valid S1 request should satisfy the schema");

        assertFalse(schema.validate(readJson("""
                {"source_sheba":"IR120990000000000000000001"}
                """)).isEmpty(), "destination_sheba is required");
        assertFalse(schema.validate(readJson("""
                {
                  "source_sheba":"001200456789",
                  "destination_sheba":"IR330990000000000000000002"
                }
                """)).isEmpty(), "Local account numbers must not be accepted as Sheba values");
        assertFalse(schema.validate(readJson("""
                {
                  "source_sheba":"IR120990000000000000000001",
                  "destination_sheba":"IR330990000000000000000002",
                  "amount":1000
                }
                """)).isEmpty(), "S1 request must reject undeclared amount fields");
    }

    @Test
    void bothSuccessfulResponseExamplesMatchTheS1Schema() {
        JsonNode response = operation.at("/responses/200");
        JsonNode schemaNode = response.at("/content/application~1json/schema");
        JsonSchema schema = compile(schemaNode);
        JsonNode examples = response.at("/content/application~1json/examples");

        assertTrue(examples.has("destinationFound"));
        assertTrue(examples.has("destinationNotFound"));
        for (Iterator<Map.Entry<String, JsonNode>> it = examples.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> example = it.next();
            JsonNode value = example.getValue().path("value");
            assertTrue(schema.validate(value).isEmpty(),
                    "S1 200 example '" + example.getKey() + "' does not match schema: "
                            + schema.validate(value));
        }

        JsonNode found = examples.path("destinationFound").path("value");
        assertTrue(found.at("/destination/found").asBoolean());
        assertTrue(found.at("/destination/active").isBoolean());
        assertTrue(found.at("/destination/account_id").isTextual());

        JsonNode notFound = examples.path("destinationNotFound").path("value");
        assertFalse(notFound.at("/destination/found").asBoolean());
        assertTrue(notFound.at("/destination/account_id").isNull());
        assertTrue(notFound.at("/destination/active").isNull());
    }

    @Test
    void sourceLimitIsAnIntegerMoneyObjectInIrr() {
        JsonNode moneySchema = contract.at("/components/schemas/Money");
        assertTrue(contains(moneySchema.path("required"), "amount"));
        assertTrue(contains(moneySchema.path("required"), "currency"));
        assertEquals("integer", moneySchema.at("/properties/amount/type").asText());
        assertEquals("int64", moneySchema.at("/properties/amount/format").asText());
        assertEquals(0, moneySchema.at("/properties/amount/minimum").asInt());
        assertTrue(contains(moneySchema.at("/properties/currency/enum"), "IRR"));
    }

    @Test
    void allDeclaredErrorResponsesUseProblemJsonAndMatchTheirExamples() {
        assertProblemResponse("400", "ValidationError", "VALIDATION_ERROR");
        assertProblemResponse("401", "InvalidToken", "UNAUTHORIZED");
        assertProblemResponse("404", "SourceNotFound", "NOT_FOUND");
        assertProblemResponse("503", "ServiceUnavailable", "SERVICE_UNAVAILABLE");
    }

    @Test
    void shebaSchemaIsIranianIbanWithExactlyTheDeclaredPattern() {
        JsonNode sheba = contract.at("/components/schemas/Sheba");
        assertEquals("string", sheba.path("type").asText());
        assertEquals("^IR[0-9]{24}$", sheba.path("pattern").asText());
        JsonSchema schema = compile(sheba);
        assertTrue(schema.validate(JSON.getNodeFactory().textNode("IR120990000000000000000001")).isEmpty());
        assertFalse(schema.validate(JSON.getNodeFactory().textNode("001200456789")).isEmpty());
        assertFalse(schema.validate(JSON.getNodeFactory().textNode("IR123")).isEmpty());
    }

    private static void assertProblemResponse(String status, String responseName, String expectedCode) {
        JsonNode responseRef = operation.at("/responses/" + status);
        assertEquals("#/components/responses/" + responseName, responseRef.path("$ref").asText());
        JsonNode response = resolve(responseRef);
        JsonNode schemaNode = response.at("/content/application~1problem+json/schema");
        assertFalse(schemaNode.isMissingNode(), status + " must return application/problem+json");
        JsonSchema schema = compile(schemaNode);
        JsonNode example = response.at("/content/application~1problem+json/example");
        assertFalse(example.isMissingNode(), status + " response example is missing");
        assertTrue(schema.validate(example).isEmpty(),
                status + " example does not match Problem schema: " + schema.validate(example));
        assertEquals(Integer.parseInt(status), example.path("status").asInt());
        assertEquals(expectedCode, example.path("code").asText());
    }

    private static JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        for (int depth = 0; current.has("$ref") && depth < 12; depth++) {
            String ref = current.path("$ref").asText();
            assertTrue(ref.startsWith("#/"), "Only local OpenAPI references are expected: " + ref);
            current = contract.at(ref.substring(1));
            assertFalse(current.isMissingNode(), "Unresolved OpenAPI reference: " + ref);
        }
        return current;
    }

    private static JsonSchema compile(JsonNode schemaNode) {
        JsonNode resolved = resolve(schemaNode);
        var root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.set("components", contract.path("components").deepCopy());
        root.set("allOf", JSON.createArrayNode().add(resolved.deepCopy()));
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root, config);
    }

    private static JsonNode readJson(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("Invalid JSON test fixture", e);
        }
    }

    private static boolean contains(JsonNode array, String expected) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (expected.equals(item.asText())) return true;
        return false;
    }
}
