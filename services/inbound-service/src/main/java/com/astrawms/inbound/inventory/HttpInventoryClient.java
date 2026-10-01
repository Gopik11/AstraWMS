package com.astrawms.inbound.inventory;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST client for inventory-service. Tenant, user and channel are propagated from {@link TenantContext}; problem
 * responses (RFC 9457) are re-thrown with the original status and code so RF clients see one error vocabulary.
 */
public class HttpInventoryClient implements InventoryClient {

    private final RestClient rest;
    private final JsonMapper json;

    public HttpInventoryClient(RestClient rest, JsonMapper json) {
        this.rest = rest;
        this.json = json;
    }

    @Override
    public ReceiveResult receive(String siteId, String idempotencyKey, ReceiveCommand command) {
        TenantContext.require(); // ServiceCallInterceptor adds tenant, user and bearer token
        try {
            JsonNode body = rest.post()
                    .uri("/api/v1/sites/{site}/inventory/receipts", siteId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(command)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode(), response.getBody().readAllBytes());
                    })
                    .body(JsonNode.class);
            return new ReceiveResult(UUID.fromString(body.get("operationId").asString()),
                    body.get("replayed").asBoolean());
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INB_INVENTORY_UNAVAILABLE",
                    "Inventory service unreachable; retry with the same Idempotency-Key");
        }
    }

    private ApiException translate(HttpStatusCode status, byte[] body) {
        if (status.is5xxServerError()) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INB_INVENTORY_UNAVAILABLE",
                    "Inventory service error " + status.value() + "; retry with the same Idempotency-Key");
        }
        try {
            JsonNode problem = json.readTree(body);
            Map<String, Object> props = new HashMap<>();
            problem.properties().forEach(e -> {
                if (!Map.of("type", 1, "title", 1, "status", 1, "detail", 1, "code", 1, "instance", 1).containsKey(e.getKey())) {
                    props.put(e.getKey(), json.treeToValue(e.getValue(), Object.class));
                }
            });
            return new ApiException(HttpStatus.valueOf(status.value()),
                    problem.path("code").asString("INVENTORY_ERROR"), problem.path("detail").asString(""), props);
        } catch (RuntimeException e) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "INB_INVENTORY_ERROR", "Unexpected inventory response " + status.value());
        }
    }
}
