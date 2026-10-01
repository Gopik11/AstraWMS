package com.astrawms.masterdata.service;

import com.astrawms.common.contracts.MasterDataEvents;
import com.astrawms.common.contracts.MasterDataEvents.ItemUpserted;
import com.astrawms.common.messaging.OutboxWriter;
import com.astrawms.common.tenancy.TenantContext;
import com.astrawms.common.web.ApiException;
import com.astrawms.masterdata.api.MasterDataDtos.ItemRequest;
import com.astrawms.masterdata.api.MasterDataDtos.ItemSite;
import com.astrawms.masterdata.api.MasterDataDtos.ItemStatus;
import com.astrawms.masterdata.api.MasterDataDtos.ItemUom;
import com.astrawms.masterdata.api.MasterDataDtos.ItemView;
import com.astrawms.masterdata.api.MasterDataDtos.Page;
import com.astrawms.masterdata.api.MasterDataDtos.SerialControl;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Item master (IF-MD-001 rules). Every change publishes {@code ItemUpserted} through the outbox in the same
 * transaction. Optimistic concurrency via version / ETag (NFR-124).
 */
@Service
public class ItemService {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ItemService(JdbcClient jdbc, OutboxWriter outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public ItemView upsert(String ownerId, String itemNo, ItemRequest r, Long expectedVersion) {
        List<ItemUom> uoms = validate(r);
        Instant now = clock.instant();
        Instant sourceChangedAt = r.sourceChangedAt() != null ? r.sourceChangedAt() : now;
        TenantContext.Scope scope = TenantContext.require();

        Optional<Long> current = jdbc.sql("""
                        select version from item where owner_id = :owner and item_no = :item for update""")
                .param("owner", ownerId).param("item", itemNo).query(Long.class).optional();
        if (expectedVersion != null && !current.map(v -> v.equals(expectedVersion)).orElse(false)) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "MD_VERSION_MISMATCH",
                    "Item was modified; current version is " + current.map(String::valueOf).orElse("none"));
        }

        jdbc.sql("""
                        insert into item (tenant_id, owner_id, item_no, description, base_uom, item_type, status,
                                          shelf_life_days, min_remaining_shelf_life_days, temperature_class, hazardous,
                                          source, source_changed_at, version, updated_at, updated_by, standard_cost)
                        values (:tenant, :owner, :item, :description, :baseUom, :itemType, :status, :shelf, :minShelf,
                                :temp, :haz, :source, :changedAt, 0, :now, :user, :cost)
                        on conflict (tenant_id, owner_id, item_no) do update set
                            description = excluded.description, base_uom = excluded.base_uom,
                            item_type = excluded.item_type, status = excluded.status,
                            shelf_life_days = excluded.shelf_life_days,
                            min_remaining_shelf_life_days = excluded.min_remaining_shelf_life_days,
                            temperature_class = excluded.temperature_class, hazardous = excluded.hazardous,
                            source = excluded.source, source_changed_at = excluded.source_changed_at,
                            version = item.version + 1, updated_at = excluded.updated_at,
                            updated_by = excluded.updated_by, standard_cost = excluded.standard_cost""")
                .param("tenant", scope.tenantId()).param("owner", ownerId).param("item", itemNo)
                .param("description", r.description()).param("baseUom", r.baseUom()).param("itemType", r.itemType())
                .param("status", r.status().name()).param("shelf", r.shelfLifeDays())
                .param("minShelf", r.minRemainingShelfLifeDays()).param("temp", r.temperatureClass())
                .param("haz", r.hazardous()).param("source", "ERP".equals(scope.channel()) ? "ERP" : "API")
                .param("changedAt", Timestamp.from(sourceChangedAt)).param("now", Timestamp.from(now))
                .param("user", scope.userId()).param("cost", r.standardCost())
                .update();

        jdbc.sql("delete from item_site where owner_id = :owner and item_no = :item")
                .param("owner", ownerId).param("item", itemNo).update();
        for (ItemSite s : r.sites()) {
            jdbc.sql("""
                            insert into item_site (tenant_id, owner_id, item_no, site_id, lot_controlled, serial_control, status)
                            values (:tenant, :owner, :item, :site, :lot, :serial, :status)""")
                    .param("tenant", scope.tenantId()).param("owner", ownerId).param("item", itemNo)
                    .param("site", s.siteId()).param("lot", s.lotControlled()).param("serial", s.serialControl().name())
                    .param("status", s.status() == null ? null : s.status().name())
                    .update();
        }
        jdbc.sql("delete from item_uom where owner_id = :owner and item_no = :item")
                .param("owner", ownerId).param("item", itemNo).update();
        for (ItemUom u : uoms) {
            try {
                jdbc.sql("""
                                insert into item_uom (tenant_id, owner_id, item_no, uom, numerator, denominator, gtin,
                                                      length_cm, width_cm, height_cm, gross_weight_kg)
                                values (:tenant, :owner, :item, :uom, :num, :den, :gtin, :l, :w, :h, :kg)""")
                        .param("tenant", scope.tenantId()).param("owner", ownerId).param("item", itemNo)
                        .param("uom", u.uom()).param("num", u.numerator()).param("den", u.denominator())
                        .param("gtin", u.gtin()).param("l", u.lengthCm()).param("w", u.widthCm())
                        .param("h", u.heightCm()).param("kg", u.grossWeightKg())
                        .update();
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("MD_GTIN_DUPLICATE",
                        "GTIN " + u.gtin() + " is already assigned to another item of owner " + ownerId);
            }
        }

        outbox.append(new OutboxWriter.Message(MasterDataEvents.TOPIC, MasterDataEvents.ITEM_UPSERTED,
                MasterDataEvents.SCHEMA_VERSION, null, null, ownerId, ownerId + ":" + itemNo,
                new ItemUpserted(ownerId, itemNo, r.baseUom(), r.status().name(), r.shelfLifeDays(),
                        r.temperatureClass(), r.hazardous(),
                        r.sites().stream().map(s -> new ItemUpserted.Site(s.siteId(), s.lotControlled(),
                                s.serialControl().name(), s.status() == null ? null : s.status().name())).toList(),
                        uoms.stream().map(u -> new ItemUpserted.Uom(u.uom(), u.numerator(), u.denominator())).toList(),
                        sourceChangedAt, r.standardCost())));
        return get(ownerId, itemNo);
    }

    @Transactional(readOnly = true)
    public ItemView get(String ownerId, String itemNo) {
        List<ItemSite> sites = jdbc.sql("""
                        select site_id, lot_controlled, serial_control, status from item_site
                        where owner_id = :owner and item_no = :item order by site_id""")
                .param("owner", ownerId).param("item", itemNo)
                .query((rs, n) -> new ItemSite(rs.getString("site_id"), rs.getBoolean("lot_controlled"),
                        SerialControl.valueOf(rs.getString("serial_control")),
                        rs.getString("status") == null ? null : ItemStatus.valueOf(rs.getString("status"))))
                .list();
        List<ItemUom> uoms = jdbc.sql("""
                        select uom, numerator, denominator, gtin, length_cm, width_cm, height_cm, gross_weight_kg
                        from item_uom where owner_id = :owner and item_no = :item order by numerator, uom""")
                .param("owner", ownerId).param("item", itemNo)
                .query((rs, n) -> new ItemUom(rs.getString("uom"), rs.getInt("numerator"), rs.getInt("denominator"),
                        rs.getString("gtin"), rs.getBigDecimal("length_cm"), rs.getBigDecimal("width_cm"),
                        rs.getBigDecimal("height_cm"), rs.getBigDecimal("gross_weight_kg")))
                .list();
        return jdbc.sql("""
                        select owner_id, item_no, description, base_uom, item_type, status, shelf_life_days,
                               min_remaining_shelf_life_days, temperature_class, hazardous, source, version,
                               updated_at, updated_by, standard_cost
                        from item where owner_id = :owner and item_no = :item""")
                .param("owner", ownerId).param("item", itemNo)
                .query((rs, n) -> new ItemView(rs.getString("owner_id"), rs.getString("item_no"),
                        rs.getString("description"), rs.getString("base_uom"), rs.getString("item_type"),
                        ItemStatus.valueOf(rs.getString("status")), (Integer) rs.getObject("shelf_life_days"),
                        (Integer) rs.getObject("min_remaining_shelf_life_days"), rs.getString("temperature_class"),
                        rs.getBoolean("hazardous"), sites, uoms, rs.getString("source"), rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by"),
                        rs.getBigDecimal("standard_cost")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("MD_ITEM_UNKNOWN", "Item " + itemNo + " of owner " + ownerId + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<ItemView> list(String ownerId, String after, int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        List<String> keys = jdbc.sql("""
                        select item_no from item where owner_id = :owner and item_no > :after
                        order by item_no limit :limit""")
                .param("owner", ownerId).param("after", after == null ? "" : after).param("limit", size + 1)
                .query(String.class).list();
        List<ItemView> items = new ArrayList<>();
        for (String key : keys.subList(0, Math.min(size, keys.size()))) {
            items.add(get(ownerId, key));
        }
        return new Page<>(items, keys.size() > size ? keys.get(size - 1) : null);
    }

    /** Returns the UoM list with the base UoM (1/1) guaranteed present. */
    private List<ItemUom> validate(ItemRequest r) {
        Set<String> catalogue = new HashSet<>(jdbc.sql("select uom from uom_catalogue").query(String.class).list());
        if (!catalogue.contains(r.baseUom())) {
            throw ApiException.unprocessable("MD_UOM_UNKNOWN", "Base UoM " + r.baseUom() + " is not in the UoM catalogue");
        }
        List<ItemUom> uoms = new ArrayList<>(r.uoms() == null ? List.of() : r.uoms());
        Set<String> seen = new HashSet<>();
        for (ItemUom u : uoms) {
            if (!catalogue.contains(u.uom())) {
                throw ApiException.unprocessable("MD_UOM_UNKNOWN", "UoM " + u.uom() + " is not in the UoM catalogue");
            }
            if (!seen.add(u.uom())) {
                throw ApiException.unprocessable("MD_UOM_DUPLICATE", "UoM " + u.uom() + " is listed twice");
            }
            if (u.uom().equals(r.baseUom()) && (u.numerator() != 1 || u.denominator() != 1)) {
                throw ApiException.unprocessable("MD_BASE_UOM_FACTOR", "The base UoM must have factor 1/1");
            }
            if (u.gtin() != null && !Gtin.isValid(u.gtin())) {
                throw ApiException.unprocessable("MD_GTIN_INVALID", "GTIN " + u.gtin() + " has an invalid check digit");
            }
        }
        if (!seen.contains(r.baseUom())) {
            uoms.addFirst(new ItemUom(r.baseUom(), 1, 1, null, null, null, null, null));
        }
        Set<String> knownSites = new HashSet<>(jdbc.sql("select site_id from site").query(String.class).list());
        Set<String> requestSites = new HashSet<>();
        for (ItemSite s : r.sites()) {
            if (!knownSites.contains(s.siteId())) {
                throw ApiException.unprocessable("MD_SITE_UNKNOWN", "Site " + s.siteId() + " does not exist");
            }
            if (!requestSites.add(s.siteId())) {
                throw ApiException.unprocessable("MD_SITE_DUPLICATE", "Site " + s.siteId() + " is listed twice");
            }
        }
        return uoms;
    }
}
