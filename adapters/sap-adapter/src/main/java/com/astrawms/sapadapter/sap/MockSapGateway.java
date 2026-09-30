package com.astrawms.sapadapter.sap;

import com.astrawms.common.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simulated SAP backend for development and tests. It behaves like the real BAPIs where the adapter depends on it:
 * <ul>
 *   <li>duplicate check on the WMS transaction ID (XBLNR): an existing document is returned, not re-posted;</li>
 *   <li>material document numbers from a 49xxxxxxxx number range, per fiscal year;</li>
 *   <li>injectable faults per delivery / material: posting period closed (M7 053), batch missing (M7 102) and
 *       lock conflicts (M3 897, transient).</li>
 * </ul>
 * Runs in its own transaction, as a remote SAP LUW would: a posted document survives an adapter-side rollback,
 * which is exactly the situation the duplicate check protects against.
 */
public class MockSapGateway implements SapGateway {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public MockSapGateway(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = SapTransientException.class)
    public Result confirmInboundDelivery(Bapi.InbDeliveryConfirmDec call) {
        return post("GR_INBOUND_DELIVERY", call.wmsTxnId(), call.delivery(), call.delivery(), call);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = SapTransientException.class)
    public Result createGoodsMovement(Bapi.GoodsmvtCreate call) {
        String material = call.items().isEmpty() ? "" : call.items().getFirst().material();
        return post("GOODS_MOVEMENT", call.header().refDocNo(), null, material, call);
    }

    private Result post(String docType, String xblnr, String vbeln, String faultKey, Object call) {
        record Doc(String number, String year) {
        }
        Optional<Doc> existing = jdbc.sql("select material_document, doc_year from mock_sap_document where xblnr = :x")
                .param("x", xblnr).query((rs, n) -> new Doc(rs.getString(1), rs.getString(2))).optional();
        if (existing.isPresent()) {
            return new Result(true, existing.get().number(), existing.get().year(), true,
                    List.of(new Bapi.Return("S", "M7", "060", "Document already posted as " + existing.get().number())));
        }
        Optional<String> fault = consumeFault(faultKey);
        if (fault.isPresent()) {
            switch (fault.get()) {
                case "LOCKED" -> throw new SapTransientException("M3 897: object " + faultKey + " is locked by user BATCHJOB");
                case "PERIOD_CLOSED" -> {
                    return failure("M7", "053", "Posting only possible in periods " + Year.now(clock) + "/10 and "
                            + Year.now(clock) + "/09 in company code 1000");
                }
                case "BATCH_MISSING" -> {
                    return failure("M7", "102", "Batch does not exist for material " + faultKey);
                }
                default -> throw new IllegalStateException("Unknown fault " + fault.get());
            }
        }
        String number = String.valueOf(jdbc.sql("select nextval('mock_sap_matdoc_seq')").query(Long.class).single());
        String year = String.valueOf(Year.now(clock).getValue());
        jdbc.sql("""
                        insert into mock_sap_document (tenant_id, material_document, doc_year, doc_type, xblnr, vbeln,
                                                       payload, created_at)
                        values (:tenant, :doc, :year, :type, :xblnr, :vbeln, cast(:payload as jsonb), :now)""")
                .param("tenant", TenantContext.tenantId()).param("doc", number).param("year", year)
                .param("type", docType).param("xblnr", xblnr).param("vbeln", vbeln)
                .param("payload", json.writeValueAsString(call)).param("now", Timestamp.from(clock.instant()))
                .update();
        return new Result(true, number, year, false,
                List.of(new Bapi.Return("S", "M7", "060", "Document " + number + " posted")));
    }

    private Optional<String> consumeFault(String key) {
        record Fault(String fault, Integer remaining) {
        }
        Optional<Fault> f = jdbc.sql("select fault, remaining from mock_sap_fault where fault_key = :k for update")
                .param("k", key).query((rs, n) -> new Fault(rs.getString(1), (Integer) rs.getObject(2))).optional();
        if (f.isEmpty()) {
            return Optional.empty();
        }
        if (f.get().remaining() != null) {
            if (f.get().remaining() <= 1) {
                jdbc.sql("delete from mock_sap_fault where fault_key = :k").param("k", key).update();
            } else {
                jdbc.sql("update mock_sap_fault set remaining = remaining - 1 where fault_key = :k").param("k", key).update();
            }
        }
        return Optional.of(f.get().fault());
    }

    private static Result failure(String id, String number, String text) {
        return new Result(false, null, null, false, List.of(new Bapi.Return("E", id, number, text)));
    }
}
