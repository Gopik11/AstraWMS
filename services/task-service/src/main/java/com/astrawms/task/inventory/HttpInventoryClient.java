package com.astrawms.task.inventory;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** REST client for inventory-service; problem codes are passed through unchanged (ADR-0005). */
public class HttpInventoryClient implements InventoryClient {

    private static final Set<String> STANDARD = Set.of("type", "title", "status", "detail", "code", "instance");

    private final RestClient rest;
    private final JsonMapper json;

    public HttpInventoryClient(RestClient rest, JsonMapper json) {
        this.rest = rest;
        this.json = json;
    }

    @Override
    public UUID moveLpn(String siteId, String idempotencyKey, String lpnId, String fromLocationId, String toLocationId) {
        TenantContext.require(); // ServiceCallInterceptor adds tenant, user and bearer token
        try {
            JsonNode body = rest.post()
                    .uri("/api/v1/sites/{site}/inventory/moves", siteId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of("fromLocationId", fromLocationId, "lpnId", lpnId, "toLocationId", toLocationId))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode(), response.getBody().readAllBytes());
                    })
                    .body(JsonNode.class);
            return UUID.fromString(body.get("operationId").asString());
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSK_INVENTORY_UNAVAILABLE",
                    "Inventory service unreachable; confirm again");
        }
    }

    @Override
    public UUID pick(String siteId, String idempotencyKey, UUID allocationId, java.math.BigDecimal qty, String toLocationId,
                     String toLpnId, java.util.List<String> serials, boolean shortClose) {
        Map<String, Object> body = new HashMap<>();
        body.put("qty", qty);
        body.put("toLocationId", toLocationId);
        body.put("toLpnId", toLpnId);
        body.put("serials", serials);
        body.put("shortClose", shortClose);
        return post("/api/v1/sites/{site}/inventory/allocations/" + allocationId + "/pick", siteId, idempotencyKey, body);
    }

    private UUID post(String path, String siteId, String idempotencyKey, Object payload) {
        TenantContext.require(); // ServiceCallInterceptor adds tenant, user and bearer token
        try {
            JsonNode body = rest.post()
                    .uri(path, siteId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode(), response.getBody().readAllBytes());
                    })
                    .body(JsonNode.class);
            return UUID.fromString(body.get("operationId").asString());
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSK_INVENTORY_UNAVAILABLE",
                    "Inventory service unreachable; confirm again");
        }
    }

    private ApiException translate(HttpStatusCode status, byte[] body) {
        if (status.is5xxServerError()) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSK_INVENTORY_UNAVAILABLE",
                    "Inventory service error " + status.value() + "; confirm again");
        }
        try {
            JsonNode problem = json.readTree(body);
            Map<String, Object> props = new HashMap<>();
            problem.properties().forEach(e -> {
                if (!STANDARD.contains(e.getKey())) {
                    props.put(e.getKey(), json.treeToValue(e.getValue(), Object.class));
                }
            });
            return new ApiException(HttpStatus.valueOf(status.value()), problem.path("code").asString("INVENTORY_ERROR"),
                    problem.path("detail").asString(""), props);
        } catch (RuntimeException e) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "TSK_INVENTORY_ERROR", "Unexpected inventory response " + status.value());
        }
    }
}
