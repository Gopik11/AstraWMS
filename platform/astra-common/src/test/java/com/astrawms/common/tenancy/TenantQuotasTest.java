package com.astrawms.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Per-tenant token buckets (ADR-0030 E), on a clock the test moves. */
class TenantQuotasTest {

    private final AtomicLong now = new AtomicLong(0);

    private TenantQuotas quotas(TenantRegistry.Mode mode) {
        TenantQuotaProperties p = new TenantQuotaProperties();
        p.setMode(mode);
        p.setPlans(Map.of("TINY", new TenantQuotaProperties.Plan(2, 3)));   // 2 per second, bursts of 3
        return new TenantQuotas(p, new TenantTags(null), now::get);
    }

    @Test
    void aBurstPassesThenTheTenantWaitsForTheRefill() {
        TenantQuotas q = quotas(TenantRegistry.Mode.ENFORCE);
        for (int i = 0; i < 3; i++) {
            assertThat(q.acquire("A", "TINY")).isEmpty();
        }
        Optional<Duration> wait = q.acquire("A", "TINY");
        assertThat(wait).contains(Duration.ofMillis(500));      // one token at 2/s
        now.addAndGet(Duration.ofMillis(500).toNanos());
        assertThat(q.acquire("A", "TINY")).isEmpty();
        assertThat(q.acquire("A", "TINY")).isPresent();
    }

    @Test
    void oneTenantOverItsRateDoesNotSlowAnother() {
        TenantQuotas q = quotas(TenantRegistry.Mode.ENFORCE);
        for (int i = 0; i < 3; i++) {
            q.acquire("A", "TINY");
        }
        assertThat(q.acquire("A", "TINY")).isPresent();
        assertThat(q.acquire("B", "TINY")).isEmpty();
    }

    @Test
    void refillNeverExceedsTheBurst() {
        TenantQuotas q = quotas(TenantRegistry.Mode.ENFORCE);
        now.addAndGet(Duration.ofHours(1).toNanos());
        for (int i = 0; i < 3; i++) {
            assertThat(q.acquire("A", "TINY")).isEmpty();
        }
        assertThat(q.acquire("A", "TINY")).isPresent();
    }

    @Test
    void reportModeCountsButNeverRefuses() {
        TenantQuotas q = quotas(TenantRegistry.Mode.REPORT);
        for (int i = 0; i < 10; i++) {
            assertThat(q.acquire("A", "TINY")).isEmpty();
        }
    }

    @Test
    void anUnknownPlanFallsBackToStandardAndAPlanChangeStartsFull() {
        TenantQuotas q = quotas(TenantRegistry.Mode.ENFORCE);
        for (int i = 0; i < 200; i++) {
            assertThat(q.acquire("A", "NO-SUCH-PLAN")).isEmpty();        // STANDARD: bursts of 200
        }
        assertThat(q.acquire("A", "NO-SUCH-PLAN")).isPresent();
        q.plan("STANDARD", 100, 1);
        assertThat(q.acquire("A", "NO-SUCH-PLAN")).isEmpty();
        assertThat(q.acquire("A", "NO-SUCH-PLAN")).isPresent();
    }

    @Test
    void metricsNameOnlyRegisteredTenants() {
        assertThat(new TenantTags(null).of("anything")).isEqualTo(TenantTags.UNREGISTERED);
    }
}
