package com.astrawms.sapadapter.api;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.sapadapter.flow.InboundDeliveryFlow;
import com.astrawms.sapadapter.flow.InboundDeliveryFlow.IdocStatus;
import com.astrawms.sapadapter.flow.SiteMapRepository;
import com.astrawms.sapadapter.mapping.MappingException;
import com.astrawms.sapadapter.sap.Delvry07;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAP adapter endpoints.
 * <ul>
 *   <li>{@code /api/v1/sap/...}: the inbound IDoc port (what the middleware calls) and adapter configuration;</li>
 *   <li>{@code /mock-sap/...}: controls and inspects the simulated SAP backend (dev/test only).</li>
 * </ul>
 */
@RestController
public class SapAdapterController {

    private final InboundDeliveryFlow deliveries;
    private final SiteMapRepository sites;
    private final JdbcClient jdbc;

    public SapAdapterController(InboundDeliveryFlow deliveries, SiteMapRepository sites, JdbcClient jdbc) {
        this.deliveries = deliveries;
        this.sites = sites;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ IDoc port

    @PostMapping("/api/v1/sap/idocs/delvry07")
    public ResponseEntity<IdocStatus> receiveDelvry(@RequestBody Delvry07 idoc) {
        if (idoc.docnum() == null || idoc.docnum().isBlank()) {
            throw ApiException.badRequest("IDOC_DOCNUM_MISSING", "DOCNUM is required");
        }
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(deliveries.receive(idoc));
        } catch (MappingException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), e.getMessage(),
                    Map.of("idocNumber", idoc.docnum(), "idocStatus", "51"));
        }
    }

    @GetMapping("/api/v1/sap/idocs/{idocNumber}")
    public IdocStatus idocStatus(@PathVariable String idocNumber) {
        return deliveries.status(idocNumber)
                .orElseThrow(() -> ApiException.notFound("IDOC_UNKNOWN", "IDoc " + idocNumber + " not found"));
    }

    public record SiteMapping(@NotBlank String siteId, @NotBlank String timeZone, @NotBlank String defaultOwner) {
    }

    @PutMapping("/api/v1/sap/site-map/{werks}")
    @Transactional
    public ResponseEntity<Void> mapPlant(@PathVariable String werks, @Valid @RequestBody SiteMapping body) {
        try {
            ZoneId.of(body.timeZone());
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("TIME_ZONE_INVALID", "Unknown time zone " + body.timeZone());
        }
        sites.upsert(werks, body.siteId(), body.timeZone(), body.defaultOwner());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ simulated SAP backend

    public record Fault(@NotBlank String faultKey, @NotBlank String fault, Integer remaining) {
    }

    @PostMapping("/mock-sap/faults")
    @Transactional
    public ResponseEntity<Void> injectFault(@Valid @RequestBody Fault f) {
        jdbc.sql("""
                        insert into mock_sap_fault (tenant_id, fault_key, fault, remaining) values (:t, :k, :f, :r)
                        on conflict (tenant_id, fault_key) do update set fault = excluded.fault, remaining = excluded.remaining""")
                .param("t", TenantContext.tenantId()).param("k", f.faultKey()).param("f", f.fault())
                .param("r", f.remaining()).update();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/mock-sap/faults/{faultKey}")
    @Transactional
    public ResponseEntity<Void> clearFault(@PathVariable String faultKey) {
        jdbc.sql("delete from mock_sap_fault where fault_key = :k").param("k", faultKey).update();
        return ResponseEntity.noContent().build();
    }

    public record MockDocument(String materialDocument, String year, String docType, String xblnr, String vbeln,
                               String payload) {
    }

    @GetMapping("/mock-sap/documents")
    @Transactional(readOnly = true)
    public List<MockDocument> documents(@RequestParam(required = false) String xblnr,
                                        @RequestParam(required = false) String vbeln) {
        return jdbc.sql("""
                        select material_document, doc_year, doc_type, xblnr, vbeln, payload::text from mock_sap_document
                        where (cast(:x as text) is null or xblnr = :x) and (cast(:v as text) is null or vbeln = :v)
                        order by material_document""")
                .param("x", xblnr).param("v", vbeln)
                .query((rs, n) -> new MockDocument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)))
                .list();
    }
}
