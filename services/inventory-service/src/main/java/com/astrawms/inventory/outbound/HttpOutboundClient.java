package com.astrawms.inventory.outbound;

import com.astrawms.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Reads the outbound commitments; when outbound cannot be reached the caller is told so (no silent zero). */
public class HttpOutboundClient implements OutboundClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOutboundClient.class);
    private final RestClient rest;

    public HttpOutboundClient(RestClient rest) {
        this.rest = rest;
    }

    @Override
    public Commitments commitments() {
        TenantContext.require(); // ServiceCallInterceptor adds tenant, user and bearer token
        try {
            JsonNode body = rest.get().uri("/api/v1/network/outbound/commitments").retrieve().body(JsonNode.class);
            List<SourceCommitment> bySource = new ArrayList<>();
            for (JsonNode r : body.get("bySource")) {
                bySource.add(new SourceCommitment(r.get("site_id").asString(), r.get("owner_id").asString(),
                        r.get("item_no").asString(), dec(r, "allocated_orders"), dec(r, "allocated_transfers"),
                        dec(r, "short_orders"), dec(r, "short_transfers")));
            }
            List<OpenTransfer> toStore = new ArrayList<>();
            for (JsonNode r : body.get("toStore")) {
                toStore.add(new OpenTransfer(r.get("site_id").asString(), r.get("owner_id").asString(),
                        r.get("item_no").asString(), r.get("from_site").asString(), dec(r, "open_qty"),
                        r.get("transfers").asString()));
            }
            return new Commitments(bySource, toStore, true);
        } catch (RestClientException e) {
            log.warn("Outbound commitments unavailable: {}", e.getMessage());
            return Commitments.UNAVAILABLE;
        }
    }

    private static BigDecimal dec(JsonNode r, String field) {
        return r.hasNonNull(field) ? r.get(field).decimalValue() : BigDecimal.ZERO;
    }
}
