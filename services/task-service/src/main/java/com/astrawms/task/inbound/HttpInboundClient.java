package com.astrawms.task.inbound;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST client for inbound-service; the RF user's token is relayed, so the inbound role and owner checks apply, on the
 * RF channel.
 */
public class HttpInboundClient implements InboundClient {

    private static final Set<String> STANDARD = Set.of("type", "title", "status", "detail", "code", "instance");

    private final RestClient rest;
    private final JsonMapper json;

    public HttpInboundClient(RestClient rest, JsonMapper json) {
        this.rest = rest;
        this.json = json;
    }

    @Override
    public JsonNode receiveAsnItem(String siteId, String docNo, String idempotencyKey, Map<String, Object> scan) {
        return post("/api/v1/sites/{site}/receipts/{doc}/receive-item", siteId, docNo, idempotencyKey, scan);
    }

    @Override
    public JsonNode receiveReturnUnit(String siteId, String rmaNo, String idempotencyKey, Map<String, Object> unit) {
        return post("/api/v1/sites/{site}/returns/{doc}/units", siteId, rmaNo, idempotencyKey, unit);
    }

    @Override
    public JsonNode closeAsn(String siteId, String docNo, Map<String, String> shortReasons) {
        return post("/api/v1/sites/{site}/receipts/{doc}/close", siteId, docNo, null,
                Map.of("shortReasons", shortReasons == null ? Map.of() : shortReasons));
    }

    @Override
    public JsonNode closeReturn(String siteId, String rmaNo) {
        return post("/api/v1/sites/{site}/returns/{doc}/close", siteId, rmaNo, null, Map.of());
    }

    private JsonNode post(String path, String siteId, String docNo, String idempotencyKey, Object payload) {
        // ServiceCallInterceptor adds tenant, user, channel and bearer token. The work comes from RF tasks, so the
        // call is on the RF channel: inbound accepts floor receiving only from RF (ADR-0021).
        TenantContext.Scope scope = TenantContext.require();
        return TenantContext.callAs(new TenantContext.Scope(scope.tenantId(), scope.userId(), "RF", scope.access()),
                () -> send(path, siteId, docNo, idempotencyKey, payload));
    }

    private JsonNode send(String path, String siteId, String docNo, String idempotencyKey, Object payload) {
        try {
            RestClient.RequestBodySpec request = rest.post().uri(path, siteId, docNo).contentType(MediaType.APPLICATION_JSON);
            if (idempotencyKey != null) {
                request = request.header("Idempotency-Key", idempotencyKey);
            }
            return request.body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw translate(response.getStatusCode(), response.getBody().readAllBytes());
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSK_INBOUND_UNAVAILABLE",
                    "Inbound service unreachable; scan again");
        }
    }

    private ApiException translate(HttpStatusCode status, byte[] body) {
        if (status.is5xxServerError()) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSK_INBOUND_UNAVAILABLE",
                    "Inbound service error " + status.value() + "; scan again");
        }
        try {
            JsonNode problem = json.readTree(body);
            Map<String, Object> props = new HashMap<>();
            problem.properties().forEach(e -> {
                if (!STANDARD.contains(e.getKey())) {
                    props.put(e.getKey(), json.treeToValue(e.getValue(), Object.class));
                }
            });
            return new ApiException(HttpStatus.valueOf(status.value()), problem.path("code").asString("INBOUND_ERROR"),
                    problem.path("detail").asString(""), props);
        } catch (RuntimeException e) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "TSK_INBOUND_ERROR", "Unexpected inbound response " + status.value());
        }
    }
}
