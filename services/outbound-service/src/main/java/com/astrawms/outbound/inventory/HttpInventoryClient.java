package com.astrawms.outbound.inventory;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.tenancy.TenantFilter;
import com.astrawms.common.web.ApiException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** REST client for inventory-service; problem codes pass through unchanged. */
public class HttpInventoryClient implements InventoryClient {

    private static final Set<String> STANDARD = Set.of("type", "title", "status", "detail", "code", "instance");

    private final RestClient rest;
    private final JsonMapper json;

    public HttpInventoryClient(RestClient rest, JsonMapper json) {
        this.rest = rest;
        this.json = json;
    }

    @Override
    public AllocateResult allocate(String siteId, String key, String orderRef, String orderLineRef, String ownerId,
                                   String itemNo, BigDecimal qty, String uom, String lotNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderRef", orderRef);
        body.put("orderLineRef", orderLineRef);
        body.put("ownerId", ownerId);
        body.put("itemNo", itemNo);
        body.put("qty", qty);
        body.put("uom", uom);
        body.put("lotNo", lotNo);
        return json.treeToValue(post("/api/v1/sites/{site}/inventory/allocations", siteId, key, body), AllocateResult.class);
    }

    @Override
    public List<IssuedLine> issue(String siteId, String key, String orderRef) {
        JsonNode result = post("/api/v1/sites/{site}/inventory/issues", siteId, key, Map.of("orderRef", orderRef));
        return json.readerForListOf(IssuedLine.class).readValue(result.get("lines"));
    }

    @Override
    public void release(String siteId, String key, String orderRef) {
        post("/api/v1/sites/{site}/inventory/allocations/release", siteId, key, Map.of("orderRef", orderRef));
    }

    private JsonNode post(String path, String siteId, String key, Object payload) {
        TenantContext.Scope scope = TenantContext.require();
        try {
            return rest.post()
                    .uri(path, siteId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(TenantFilter.TENANT_HEADER, scope.tenantId())
                    .header(TenantFilter.USER_HEADER, scope.userId())
                    .header(TenantFilter.CHANNEL_HEADER, scope.channel())
                    .header("Idempotency-Key", key)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode(), response.getBody().readAllBytes());
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OUT_INVENTORY_UNAVAILABLE", "Inventory service unreachable");
        }
    }

    private ApiException translate(HttpStatusCode status, byte[] body) {
        if (status.is5xxServerError()) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OUT_INVENTORY_UNAVAILABLE",
                    "Inventory service error " + status.value());
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
            return new ApiException(HttpStatus.BAD_GATEWAY, "OUT_INVENTORY_ERROR", "Unexpected inventory response " + status.value());
        }
    }
}
