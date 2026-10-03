package com.astrawms.sapadapter.flow;

import com.astrawms.common.web.ApiException;
import com.astrawms.sapadapter.mapping.MatmasMapper;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The master data service's item API, called with the ERP middleware's own token (relayed), so master data records
 * the item as maintained by the ERP (ADR-0022).
 */
public interface MasterDataClient {

    Optional<JsonNode> getItem(String ownerId, String itemNo);

    void putItem(String ownerId, String itemNo, MatmasMapper.Item item);

    final class Http implements MasterDataClient {

        private final RestClient rest;
        private final JsonMapper json;

        public Http(RestClient rest, JsonMapper json) {
            this.rest = rest;
            this.json = json;
        }

        @Override
        public Optional<JsonNode> getItem(String ownerId, String itemNo) {
            try {
                return Optional.ofNullable(rest.get().uri("/api/v1/items/{o}/{i}", ownerId, itemNo).retrieve()
                        .onStatus(s -> s.value() == 404, (req, res) -> {
                            throw new NotFound();
                        })
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw problem(res.getStatusCode(), res.getBody().readAllBytes());
                        })
                        .body(JsonNode.class));
            } catch (NotFound e) {
                return Optional.empty();
            } catch (ResourceAccessException e) {
                throw unavailable();
            }
        }

        @Override
        public void putItem(String ownerId, String itemNo, MatmasMapper.Item item) {
            try {
                rest.put().uri("/api/v1/items/{o}/{i}", ownerId, itemNo).contentType(MediaType.APPLICATION_JSON)
                        .body(item).retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw problem(res.getStatusCode(), res.getBody().readAllBytes());
                        })
                        .toBodilessEntity();
            } catch (ResourceAccessException e) {
                throw unavailable();
            }
        }

        private ApiException problem(HttpStatusCode status, byte[] body) {
            String code = "MD_REJECTED";
            String detail = "Master data answered " + status.value();
            try {
                JsonNode p = json.readTree(body);
                code = p.path("code").asString(code);
                detail = p.path("detail").asString(detail);
            } catch (RuntimeException ignored) {
                // not a problem document
            }
            return new ApiException(status.is5xxServerError() ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
        }

        private static ApiException unavailable() {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MD_UNAVAILABLE", "Master data service unreachable");
        }

        private static final class NotFound extends RuntimeException {
            NotFound() {
                super(null, null, false, false);
            }
        }
    }
}
