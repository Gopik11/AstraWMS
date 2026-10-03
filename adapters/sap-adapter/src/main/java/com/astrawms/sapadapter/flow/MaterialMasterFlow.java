package com.astrawms.sapadapter.flow;

import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.sapadapter.flow.InboundDeliveryFlow.IdocStatus;
import com.astrawms.sapadapter.mapping.MappingException;
import com.astrawms.sapadapter.mapping.MatmasMapper;
import com.astrawms.sapadapter.sap.Matmas05;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * MATMAS05 material master from SAP into the AstraWMS item master (ADR-0022). Synchronous: the IDoc status is 53 when
 * master data accepted the item, 51 with the reason when it did not (mapping error, unknown unit, duplicate GTIN).
 * Master data publishes {@code ItemUpserted} to every service as for items maintained in the UI.
 */
@Service
public class MaterialMasterFlow {

    private final SiteMapRepository sites;
    private final MasterDataClient masterData;
    private final JdbcClient jdbc;
    private final Clock clock;

    public MaterialMasterFlow(SiteMapRepository sites, MasterDataClient masterData, JdbcClient jdbc, Clock clock) {
        this.sites = sites;
        this.masterData = masterData;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = {MappingException.class, ApiException.class})
    public IdocStatus receive(Matmas05 idoc) {
        String matnr = idoc.e1maram() == null ? null : idoc.e1maram().matnr();
        try {
            // A first pass finds the owner (the plants' default owner), then the current item is merged in.
            MatmasMapper.Mapped probe = MatmasMapper.map(idoc, sites::byPlant, List.of(), null, clock.instant());
            Optional<JsonNode> current = masterData.getItem(probe.ownerId(), probe.itemNo());
            List<MatmasMapper.Site> existing = new ArrayList<>();
            String temperature = null;
            if (current.isPresent()) {
                current.get().path("sites").forEach(s -> existing.add(new MatmasMapper.Site(s.path("siteId").asString(),
                        s.path("lotControlled").asBoolean(), s.path("serialControl").asString(),
                        s.hasNonNull("status") ? s.get("status").asString() : null)));
                temperature = current.get().hasNonNull("temperatureClass")
                        ? current.get().get("temperatureClass").asString() : null;
            }
            MatmasMapper.Mapped mapped = MatmasMapper.map(idoc, sites::byPlant, existing, temperature, clock.instant());
            masterData.putItem(mapped.ownerId(), mapped.itemNo(), mapped.item());
            String text = "Material " + mapped.itemNo() + " updated in AstraWMS"
                    + (mapped.skipped().isEmpty() ? "" : " (skipped: " + String.join("; ", mapped.skipped()) + ")");
            return save(idoc, matnr, "53", text);
        } catch (MappingException e) {
            save(idoc, matnr, "51", e.code() + ": " + e.getMessage());
            throw e;
        } catch (ApiException e) {
            save(idoc, matnr, "51", e.code() + ": " + e.getMessage());
            throw e;
        }
    }

    private IdocStatus save(Matmas05 idoc, String matnr, String status, String text) {
        String t = text.length() > 500 ? text.substring(0, 500) : text;
        jdbc.sql("""
                        insert into idoc_status (tenant_id, idoc_number, message_type, direction, vbeln, status,
                                                 status_text, message_id, updated_at)
                        values (:t, :idoc, :type, 'INBOUND_TO_WMS', :doc, :status, :text, null, :now)
                        on conflict (tenant_id, idoc_number) do update set status = excluded.status,
                            status_text = excluded.status_text, updated_at = excluded.updated_at""")
                .param("t", TenantContext.tenantId()).param("idoc", idoc.docnum())
                .param("type", idoc.mestyp() == null ? "MATMAS" : idoc.mestyp()).param("doc", matnr)
                .param("status", status).param("text", t).param("now", Timestamp.from(clock.instant())).update();
        return new IdocStatus(idoc.docnum(), idoc.mestyp(), matnr, status, t, null);
    }
}
