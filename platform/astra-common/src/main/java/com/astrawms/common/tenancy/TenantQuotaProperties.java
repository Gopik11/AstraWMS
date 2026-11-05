package com.astrawms.common.tenancy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Request quotas per tenant plan (ADR-0030 E), under {@code astra.tenancy.quotas}:
 * <pre>
 * astra.tenancy.quotas.mode: ENFORCE          # or REPORT: count, never refuse
 * astra.tenancy.quotas.plans.STANDARD.requests-per-second: 100
 * astra.tenancy.quotas.plans.STANDARD.burst: 200
 * </pre>
 * A tenant's plan comes from the tenant registry; a plan not configured here falls back to {@code STANDARD}. The rate
 * applies per service replica.
 */
@ConfigurationProperties("astra.tenancy.quotas")
public class TenantQuotaProperties {

    public static final String DEFAULT_PLAN = "STANDARD";

    public record Plan(double requestsPerSecond, int burst) {
    }

    private TenantRegistry.Mode mode = TenantRegistry.Mode.ENFORCE;
    private Map<String, Plan> plans = new LinkedHashMap<>(Map.of(
            DEFAULT_PLAN, new Plan(100, 200),
            "LARGE", new Plan(400, 800)));

    public TenantRegistry.Mode getMode() {
        return mode;
    }

    public void setMode(TenantRegistry.Mode mode) {
        this.mode = mode;
    }

    public Map<String, Plan> getPlans() {
        return plans;
    }

    public void setPlans(Map<String, Plan> plans) {
        Map<String, Plan> merged = new LinkedHashMap<>(this.plans);
        merged.putAll(plans);
        this.plans = merged;
    }
}
