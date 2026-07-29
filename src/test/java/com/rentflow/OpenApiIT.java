package com.rentflow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.rentflow.dto.InventoryItemDTO;
import com.rentflow.support.PostgresIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiIT extends PostgresIntegrationTest {

    private static final Set<String> INVENTORY_FIELDS = Set.of("serialNumber", "type", "name", "status");
    private static final Set<String> INVENTORY_STATUSES =
            Set.of("AVAILABLE", "RESERVED", "RENTED", "INSPECTION_REQUIRED", "UNDER_MAINTENANCE", "RETIRED");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode document;

    @BeforeEach
    void loadOpenApiDocument() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse();
        document = objectMapper.readTree(response.getContentAsByteArray());
    }

    @Test
    void exposesOnlyTheFiveUnauthenticatedInventoryOperations() {
        assertThat(document.path("openapi").asString()).startsWith("3.");
        assertThat(document.at("/info/title").asString()).isEqualTo("RentFlow Inventory API");
        assertThat(document.at("/info/version").asString()).isEqualTo("v1");
        assertThat(document.path("tags").get(0).path("name").asString()).isEqualTo("Inventory");

        JsonNode paths = document.path("paths");
        assertThat(names(paths)).containsExactlyInAnyOrder("/api/v1/inventory", "/api/v1/inventory/{serialNumber}");
        assertThat(operation("/api/v1/inventory", "post").path("operationId").asString())
                .isEqualTo("createInventoryItem");
        assertThat(operation("/api/v1/inventory", "get").path("operationId").asString())
                .isEqualTo("listInventoryItems");
        assertThat(operation("/api/v1/inventory/{serialNumber}", "get")
                        .path("operationId")
                        .asString())
                .isEqualTo("getInventoryItem");
        assertThat(operation("/api/v1/inventory/{serialNumber}", "put")
                        .path("operationId")
                        .asString())
                .isEqualTo("replaceInventoryItem");
        assertThat(operation("/api/v1/inventory/{serialNumber}", "delete")
                        .path("operationId")
                        .asString())
                .isEqualTo("deleteInventoryItem");
        assertThat(paths.path("/api/v1/inventory").has("patch")).isFalse();
        assertThat(paths.path("/api/v1/inventory/{serialNumber}").has("patch")).isFalse();
        assertThat(names(paths))
                .noneMatch(path -> path.startsWith("/actuator") || path.equals("/livez") || path.equals("/readyz"));

        assertThat(document.has("security")).isFalse();
        assertThat(document.at("/components/securitySchemes").isMissingNode()
                        || document.at("/components/securitySchemes").isEmpty())
                .isTrue();
        for (JsonNode operation : operations()) {
            assertThat(operation.has("security")).isFalse();
        }
    }

    @Test
    void locksBusinessAndProblemSchemas() {
        assertSchemaProperties("InventoryItemDTO", INVENTORY_FIELDS);
        assertThat(texts(schema("InventoryItemDTO").path("required")))
                .containsExactlyInAnyOrderElementsOf(INVENTORY_FIELDS);

        JsonNode requestProperties = schema("InventoryItemDTO").path("properties");
        JsonNode serialNumber = resolved(requestProperties.path("serialNumber"));
        assertThat(serialNumber.path("minLength").asInt()).isEqualTo(1);
        assertThat(serialNumber.path("maxLength").asInt()).isEqualTo(64);
        assertThat(serialNumber.path("pattern").asString()).isEqualTo(InventoryItemDTO.SERIAL_NUMBER_PATTERN);
        assertThat(resolved(requestProperties.path("type")).path("minLength").asInt())
                .isEqualTo(1);
        assertThat(resolved(requestProperties.path("type")).path("maxLength").asInt())
                .isEqualTo(100);
        assertThat(resolved(requestProperties.path("name")).path("minLength").asInt())
                .isEqualTo(1);
        assertThat(resolved(requestProperties.path("name")).path("maxLength").asInt())
                .isEqualTo(200);
        assertThat(enumValues(requestProperties.path("status")))
                .containsExactlyInAnyOrderElementsOf(INVENTORY_STATUSES);
        assertThat(enumValues(schema("InventoryItemDTO").path("properties").path("status")))
                .containsExactlyInAnyOrderElementsOf(INVENTORY_STATUSES);

        JsonNode pageResponse = resolved(
                responseSchema(operation("/api/v1/inventory", "get"), "200", MediaType.APPLICATION_JSON_VALUE));
        assertThat(names(pageResponse.path("properties"))).containsExactlyInAnyOrder("content", "page");
        JsonNode contentSchema = resolved(pageResponse.path("properties").path("content"));
        assertThat(contentSchema.path("type").asString()).isEqualTo("array");
        assertThat(contentSchema.path("items").path("$ref").asString()).endsWith("/InventoryItemDTO");
        JsonNode pageMetadata = resolved(pageResponse.path("properties").path("page"));
        assertThat(names(pageMetadata.path("properties")))
                .containsExactlyInAnyOrder("size", "number", "totalElements", "totalPages");
        assertSchemaProperties(
                "ProblemResponse", Set.of("type", "title", "status", "detail", "instance", "code", "violations"));
        assertThat(texts(schema("ProblemResponse").path("required")))
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
        assertSchemaProperties("ViolationResponse", Set.of("field", "message"));
    }

    @Test
    void documentsPaginationFiltersSortingAndRequestBodies() {
        JsonNode list = operation("/api/v1/inventory", "get");
        assertThat(parameterNames(list))
                .containsExactlyInAnyOrder("page", "size", "status", "type", "sort", "direction");

        assertParameter(parameter(list, "page"), "0", "0", null);
        assertParameter(parameter(list, "size"), "20", "1", "100");
        assertThat(enumValues(parameter(list, "status").path("schema")))
                .containsExactlyInAnyOrderElementsOf(INVENTORY_STATUSES);
        assertThat(resolved(parameter(list, "type").path("schema"))
                        .path("minLength")
                        .asInt())
                .isEqualTo(1);
        assertThat(resolved(parameter(list, "type").path("schema"))
                        .path("maxLength")
                        .asInt())
                .isEqualTo(100);
        assertThat(resolved(parameter(list, "sort").path("schema"))
                        .path("default")
                        .asString())
                .isEqualTo("serialNumber");
        assertThat(enumValues(parameter(list, "sort").path("schema")))
                .containsExactlyInAnyOrder("serialNumber", "type", "name", "status");
        assertThat(resolved(parameter(list, "direction").path("schema"))
                        .path("default")
                        .asString())
                .isEqualTo("asc");
        assertThat(enumValues(parameter(list, "direction").path("schema"))).containsExactlyInAnyOrder("asc", "desc");

        for (String method : List.of("get", "put", "delete")) {
            JsonNode serialParameter = parameter(operation("/api/v1/inventory/{serialNumber}", method), "serialNumber");
            assertThat(serialParameter.path("required").asBoolean()).isTrue();
            assertThat(resolved(serialParameter.path("schema")).path("pattern").asString())
                    .isEqualTo(InventoryItemDTO.SERIAL_NUMBER_PATTERN);
        }

        assertRequestBodySchema(operation("/api/v1/inventory", "post"), "InventoryItemDTO");
        assertRequestBodySchema(operation("/api/v1/inventory/{serialNumber}", "put"), "InventoryItemDTO");
    }

    @Test
    void documentsSuccessAndApplicableProblemResponses() {
        JsonNode create = operation("/api/v1/inventory", "post");
        assertResponseCodes(create, "201", "400", "406", "409", "415", "500");
        assertResponseSchema(create, "201", MediaType.APPLICATION_JSON_VALUE, "InventoryItemDTO");
        assertThat(create.at("/responses/201/headers/Location/schema/format").asString())
                .isEqualTo("uri");

        JsonNode list = operation("/api/v1/inventory", "get");
        assertResponseCodes(list, "200", "400", "406", "500");
        JsonNode pageResponse = resolved(responseSchema(list, "200", MediaType.APPLICATION_JSON_VALUE));
        assertThat(names(pageResponse.path("properties"))).containsExactlyInAnyOrder("content", "page");

        JsonNode get = operation("/api/v1/inventory/{serialNumber}", "get");
        assertResponseCodes(get, "200", "400", "404", "406", "500");
        assertResponseSchema(get, "200", MediaType.APPLICATION_JSON_VALUE, "InventoryItemDTO");

        JsonNode replace = operation("/api/v1/inventory/{serialNumber}", "put");
        assertResponseCodes(replace, "200", "400", "404", "406", "415", "500");
        assertResponseSchema(replace, "200", MediaType.APPLICATION_JSON_VALUE, "InventoryItemDTO");

        JsonNode delete = operation("/api/v1/inventory/{serialNumber}", "delete");
        assertResponseCodes(delete, "204", "400", "404", "500");
        assertThat(delete.at("/responses/204").has("content")).isFalse();

        assertProblemSchemas(create, Set.of("400", "406", "409", "415", "500"));
        assertProblemSchemas(list, Set.of("400", "406", "500"));
        assertProblemSchemas(get, Set.of("400", "404", "406", "500"));
        assertProblemSchemas(replace, Set.of("400", "404", "406", "415", "500"));
        assertProblemSchemas(delete, Set.of("400", "404", "500"));
    }

    @Test
    void publishesSchemaConformingExamplesAndInteractiveSwaggerUi() throws Exception {
        JsonNode inventoryExample = schemaExample("InventoryItemDTO");
        assertExampleMatchesSchema(inventoryExample, schema("InventoryItemDTO"));
        assertThat(inventoryExample.path("serialNumber").asString()).isEqualTo("DRILL-001");
        assertThat(inventoryExample.path("status").asString()).isEqualTo("AVAILABLE");

        JsonNode problemExample = schemaExample("ProblemResponse");
        assertExampleMatchesSchema(problemExample, schema("ProblemResponse"));
        assertThat(problemExample.path("type").asString()).isEqualTo("urn:rentflow:problem:validation-failed");
        assertThat(problemExample.path("code").asString()).isEqualTo("VALIDATION_FAILED");

        String redirect = mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(redirect).isNotBlank();
        mockMvc.perform(get(redirect))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    private JsonNode operation(String path, String method) {
        return document.path("paths").path(path).path(method);
    }

    private List<JsonNode> operations() {
        return List.of(
                operation("/api/v1/inventory", "post"),
                operation("/api/v1/inventory", "get"),
                operation("/api/v1/inventory/{serialNumber}", "get"),
                operation("/api/v1/inventory/{serialNumber}", "put"),
                operation("/api/v1/inventory/{serialNumber}", "delete"));
    }

    private JsonNode schema(String name) {
        return document.at("/components/schemas/" + name);
    }

    private void assertSchemaProperties(String name, Set<String> expected) {
        assertThat(names(schema(name).path("properties"))).containsExactlyInAnyOrderElementsOf(expected);
    }

    private Set<String> names(JsonNode object) {
        return new LinkedHashSet<>(object.propertyNames());
    }

    private Set<String> texts(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private Set<String> enumValues(JsonNode rawSchema) {
        JsonNode valueSchema = resolved(rawSchema);
        Set<String> values = texts(valueSchema.path("enum"));
        for (String composition : List.of("allOf", "oneOf", "anyOf")) {
            valueSchema.path(composition).forEach(schema -> values.addAll(enumValues(schema)));
        }
        return values;
    }

    private JsonNode resolved(JsonNode schema) {
        String reference = schema.path("$ref").asString();
        return reference.isEmpty() ? schema : document.at(reference.substring(1));
    }

    private Set<String> parameterNames(JsonNode operation) {
        Set<String> names = new LinkedHashSet<>();
        operation
                .path("parameters")
                .forEach(parameter -> names.add(parameter.path("name").asString()));
        return names;
    }

    private JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asString())) {
                return parameter;
            }
        }
        throw new AssertionError("Missing OpenAPI parameter: " + name);
    }

    private void assertParameter(JsonNode parameter, String defaultValue, String minimum, String maximum) {
        JsonNode parameterSchema = resolved(parameter.path("schema"));
        assertThat(parameterSchema.path("default").asString()).isEqualTo(defaultValue);
        assertThat(parameterSchema.path("minimum").asString()).isEqualTo(minimum);
        if (maximum == null) {
            assertThat(parameterSchema.has("maximum")).isFalse();
        } else {
            assertThat(parameterSchema.path("maximum").asString()).isEqualTo(maximum);
        }
    }

    private void assertRequestBodySchema(JsonNode operation, String schemaName) {
        assertThat(operation.at("/requestBody/required").asBoolean()).isTrue();
        assertThat(operation
                        .at("/requestBody/content/application~1json/schema/$ref")
                        .asString())
                .endsWith("/" + schemaName);
    }

    private void assertResponseCodes(JsonNode operation, String... responseCodes) {
        assertThat(names(operation.path("responses"))).containsExactlyInAnyOrder(responseCodes);
    }

    private void assertResponseSchema(JsonNode operation, String status, String mediaType, String schemaName) {
        assertThat(responseSchema(operation, status, mediaType).path("$ref").asString())
                .endsWith("/" + schemaName);
    }

    private JsonNode responseSchema(JsonNode operation, String status, String mediaType) {
        String escapedMediaType = mediaType.replace("/", "~1");
        return operation.at("/responses/" + status + "/content/" + escapedMediaType + "/schema");
    }

    private void assertProblemSchemas(JsonNode operation, Set<String> responseCodes) {
        for (String responseCode : responseCodes) {
            assertResponseSchema(operation, responseCode, MediaType.APPLICATION_PROBLEM_JSON_VALUE, "ProblemResponse");
        }
    }

    private JsonNode schemaExample(String schemaName) throws Exception {
        JsonNode example = schema(schemaName).path("example");
        if (example.isMissingNode() && schema(schemaName).path("examples").isArray()) {
            example = schema(schemaName).path("examples").get(0);
        }
        assertThat(example.isMissingNode()).isFalse();
        if (example.isString()) {
            return objectMapper.readTree(example.asString());
        }
        return example;
    }

    private void assertExampleMatchesSchema(JsonNode example, JsonNode rawSchema) {
        JsonNode exampleSchema = resolved(rawSchema);
        if (exampleSchema.has("properties")) {
            assertThat(example.isObject()).isTrue();
            assertThat(names(example)).isSubsetOf(names(exampleSchema.path("properties")));
            assertThat(names(example)).containsAll(texts(exampleSchema.path("required")));
            example.forEachEntry((name, value) -> assertExampleMatchesSchema(
                    value, exampleSchema.path("properties").path(name)));
            return;
        }
        if ("array".equals(exampleSchema.path("type").asString())) {
            assertThat(example.isArray()).isTrue();
            example.forEach(item -> assertExampleMatchesSchema(item, exampleSchema.path("items")));
            return;
        }
        if ("integer".equals(exampleSchema.path("type").asString())) {
            assertThat(example.isIntegralNumber()).isTrue();
            return;
        }
        if ("string".equals(exampleSchema.path("type").asString())) {
            assertThat(example.isString()).isTrue();
            Set<String> allowedValues = enumValues(exampleSchema);
            if (!allowedValues.isEmpty()) {
                assertThat(allowedValues).contains(example.asString());
            }
        }
    }
}
