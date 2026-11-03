package com.astrawms.task.labels;

import com.astrawms.common.tenancy.TenantContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Asks master data for a label's state. When master data cannot be reached it answers {@link LabelClient#UNREACHABLE};
 * whether RF work goes on is the site's label-check policy (ADR-0029), not this client's decision.
 */
public class HttpLabelClient implements LabelClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLabelClient.class);
    private final RestClient rest;

    public HttpLabelClient(RestClient rest) {
        this.rest = rest;
    }

    @Override
    public Optional<String> status(String siteId, String type, String barcode) {
        TenantContext.require(); // ServiceCallInterceptor adds tenant, user and bearer token
        try {
            JsonNode body = rest.get()
                    .uri("/api/v1/sites/{site}/labels/printed/status?type={type}&scan={scan}", siteId, type, barcode)
                    .retrieve().body(JsonNode.class);
            return body == null || !body.hasNonNull("status") ? Optional.empty() : Optional.of(body.get("status").asString());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Label check of {} {} at {}: master data unavailable ({})", type, barcode, siteId, e.getMessage());
            return Optional.of(UNREACHABLE);
        }
    }
}
