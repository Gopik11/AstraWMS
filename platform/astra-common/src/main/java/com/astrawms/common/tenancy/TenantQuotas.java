package com.astrawms.common.tenancy;

import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Per-tenant request quotas (ADR-0030 E): a token bucket per tenant, sized by the tenant's plan, so one tenant over
 * its rate is refused (429 {@code TENANT_RATE_LIMITED} with {@code Retry-After}) while the others continue. Applied by
 * {@link TenantFilter} after the token is validated, to the tenant's own callers (users, the ERP integration), not to
 * the services' calls on their behalf: those are part of a request already counted. In memory, per replica.
 *
 * <p>In {@link TenantRegistry.Mode#REPORT} nothing is refused; what would be is counted in
 * {@code astra_tenant_rate_limited_total{enforced="false"}}.
 */
public class TenantQuotas {

    private final Map<String, TenantQuotaProperties.Plan> plans;
    private final AtomicReference<TenantRegistry.Mode> mode;
    private final TenantTags tags;
    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TenantQuotas(TenantQuotaProperties properties, TenantTags tags) {
        this(properties, tags, System::nanoTime);
    }

    TenantQuotas(TenantQuotaProperties properties, TenantTags tags, LongSupplier nanoTime) {
        this.plans = new ConcurrentHashMap<>(properties.getPlans());
        this.mode = new AtomicReference<>(properties.getMode());
        this.tags = tags;
        this.nanoTime = nanoTime;
    }

    public TenantRegistry.Mode mode() {
        return mode.get();
    }

    public void mode(TenantRegistry.Mode next) {
        mode.set(next);
    }

    /** Sets (or replaces) a plan's rate; its tenants' buckets start again full. */
    public void plan(String name, double requestsPerSecond, int burst) {
        plans.put(name, new TenantQuotaProperties.Plan(requestsPerSecond, burst));
        buckets.keySet().removeIf(k -> k.endsWith("|" + name));
    }

    /**
     * Takes one request from the tenant's bucket.
     *
     * @return how long to wait before retrying, when the request is refused; empty when it may proceed
     */
    public Optional<Duration> acquire(String tenantId, String planName) {
        String plan = planName != null && plans.containsKey(planName) ? planName : TenantQuotaProperties.DEFAULT_PLAN;
        TenantQuotaProperties.Plan p = plans.get(plan);
        long wait = buckets.computeIfAbsent(tenantId + "|" + plan, k -> new Bucket(p.requestsPerSecond(), p.burst(), nanoTime.getAsLong()))
                .take(nanoTime.getAsLong());
        String tag = tags.of(tenantId);
        Metrics.counter("astra.tenant.requests", "tenant", tag).increment();
        if (wait == 0) {
            return Optional.empty();
        }
        boolean enforced = mode.get() == TenantRegistry.Mode.ENFORCE;
        Metrics.counter("astra.tenant.rate.limited", "tenant", tag, "enforced", String.valueOf(enforced)).increment();
        return enforced ? Optional.of(Duration.ofNanos(wait)) : Optional.empty();
    }

    /** For the Operations page: the mode and each plan's rate. */
    public Map<String, Object> snapshot() {
        return Map.of("mode", mode.get().name(), "plans", new TreeMap<>(plans));
    }

    /** A token bucket: {@code capacity} tokens, refilled at {@code rate} per second. */
    static final class Bucket {
        private final double rate;
        private final double capacity;
        private double tokens;
        private long last;

        Bucket(double rate, double capacity, long now) {
            this.rate = rate;
            this.capacity = capacity;
            this.tokens = capacity;
            this.last = now;
        }

        /** @return 0 when a token was taken, else the nanoseconds until one is available (never taken then) */
        synchronized long take(long now) {
            tokens = Math.min(capacity, tokens + (now - last) / 1e9 * rate);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / rate * 1e9));
        }
    }
}
