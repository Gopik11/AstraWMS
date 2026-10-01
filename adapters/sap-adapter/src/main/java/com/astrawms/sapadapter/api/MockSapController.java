package com.astrawms.sapadapter.api;

import com.astrawms.common.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controls and inspects the simulated SAP backend: fault injection and posted documents. Exists only while the mock
 * gateway is active ({@code astra.sap.gateway=mock}) and is restricted to solution administrators.
 */
@RestController
@RequestMapping("/mock-sap")
@ConditionalOnProperty(name = "astra.sap.gateway", havingValue = "mock", matchIfMissing = true)
@PreAuthorize("hasRole('SOLUTION_ADMIN')")
public class MockSapController {

    private final JdbcClient jdbc;

    public MockSapController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Fault(@NotBlank String faultKey, @NotBlank String fault, Integer remaining) {
    }

    @PostMapping("/faults")
    @Transactional
    public ResponseEntity<Void> injectFault(@Valid @RequestBody Fault f) {
        jdbc.sql("""
                        insert into mock_sap_fault (tenant_id, fault_key, fault, remaining) values (:t, :k, :f, :r)
                        on conflict (tenant_id, fault_key) do update set fault = excluded.fault, remaining = excluded.remaining""")
                .param("t", TenantContext.tenantId()).param("k", f.faultKey()).param("f", f.fault())
                .param("r", f.remaining()).update();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/faults/{faultKey}")
    @Transactional
    public ResponseEntity<Void> clearFault(@PathVariable String faultKey) {
        jdbc.sql("delete from mock_sap_fault where fault_key = :k").param("k", faultKey).update();
        return ResponseEntity.noContent().build();
    }

    public record MockDocument(String materialDocument, String year, String docType, String xblnr, String vbeln,
                               String payload) {
    }

    @GetMapping("/documents")
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
