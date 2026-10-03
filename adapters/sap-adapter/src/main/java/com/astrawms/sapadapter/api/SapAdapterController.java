package com.astrawms.sapadapter.api;

import com.astrawms.common.web.ApiException;
import com.astrawms.sapadapter.flow.InboundDeliveryFlow;
import com.astrawms.sapadapter.flow.InboundDeliveryFlow.IdocStatus;
import com.astrawms.sapadapter.flow.SiteMapRepository;
import com.astrawms.sapadapter.mapping.MappingException;
import com.astrawms.sapadapter.sap.Delvry07;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SAP adapter endpoints: the inbound IDoc port (what the middleware calls, role ERP_INTEGRATION) and adapter
 * configuration. The simulated SAP backend has its own controller, {@link MockSapController}.
 */
@RestController
public class SapAdapterController {

    private final InboundDeliveryFlow deliveries;
    private final SiteMapRepository sites;
    private final com.astrawms.sapadapter.flow.MaterialMasterFlow materials;

    public SapAdapterController(InboundDeliveryFlow deliveries, SiteMapRepository sites,
                                com.astrawms.sapadapter.flow.MaterialMasterFlow materials) {
        this.deliveries = deliveries;
        this.sites = sites;
        this.materials = materials;
    }

    /** MATMAS05 material master (ADR-0022): 200 with status 53 when the item master took it, else 422 / status 51. */
    @PreAuthorize("hasRole('ERP_INTEGRATION')")
    @PostMapping("/api/v1/sap/idocs/matmas05")
    public IdocStatus receiveMatmas(@RequestBody com.astrawms.sapadapter.sap.Matmas05 idoc) {
        if (idoc.docnum() == null || idoc.docnum().isBlank()) {
            throw ApiException.badRequest("IDOC_DOCNUM_MISSING", "DOCNUM is required");
        }
        // Master data records items it receives on the ERP channel as maintained by the ERP.
        com.astrawms.common.tenancy.TenantContext.Scope me = com.astrawms.common.tenancy.TenantContext.require();
        try {
            return com.astrawms.common.tenancy.TenantContext.callAs(new com.astrawms.common.tenancy.TenantContext.Scope(
                    me.tenantId(), me.userId(), "ERP", me.access()), () -> materials.receive(idoc));
        } catch (MappingException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.code(), e.getMessage(),
                    Map.of("idocNumber", idoc.docnum(), "idocStatus", "51"));
        }
    }

    // ------------------------------------------------------------------ IDoc port

    @PreAuthorize("hasRole('ERP_INTEGRATION')")
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

    @PreAuthorize("hasAnyRole('ERP_INTEGRATION','SUPERVISOR','SOLUTION_ADMIN')")
    @GetMapping("/api/v1/sap/idocs/{idocNumber}")
    public IdocStatus idocStatus(@PathVariable String idocNumber) {
        return deliveries.status(idocNumber)
                .orElseThrow(() -> ApiException.notFound("IDOC_UNKNOWN", "IDoc " + idocNumber + " not found"));
    }

    public record SiteMapping(@NotBlank String siteId, @NotBlank String timeZone, @NotBlank String defaultOwner) {
    }

    @PreAuthorize("hasRole('SOLUTION_ADMIN')")
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
}
