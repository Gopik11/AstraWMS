package com.astrawms.common.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WmsTxnIdTest {

    @Test
    void idsAreSixteenCharactersAndValid() {
        String id = WmsTxnId.next();
        assertThat(id).hasSize(16).startsWith("W");
        assertThat(WmsTxnId.isValid(id)).isTrue();
    }

    @Test
    void idsSortInCreationOrder() {
        String earlier = WmsTxnId.next(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        String later = WmsTxnId.next(Clock.fixed(Instant.parse("2026-01-01T00:00:00.001Z"), ZoneOffset.UTC));
        assertThat(earlier).isLessThan(later);
    }

    @Test
    void idsAreUniqueAndMonotonicUnderLoad() {
        Set<String> ids = new HashSet<>();
        String previous = "";
        for (int i = 0; i < 100_000; i++) {
            String id = WmsTxnId.next();
            assertThat(id).isGreaterThan(previous);
            previous = id;
            ids.add(id);
        }
        assertThat(ids).hasSize(100_000);
    }

    @Test
    void sameMillisecondStillIncreases() {
        Clock frozen = Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
        String a = WmsTxnId.next(frozen);
        String b = WmsTxnId.next(frozen);
        assertThat(b).isGreaterThan(a);
    }

    @Test
    void rejectsMalformedIds() {
        assertThat(WmsTxnId.isValid(null)).isFalse();
        assertThat(WmsTxnId.isValid("W123")).isFalse();
        assertThat(WmsTxnId.isValid("X000000000000000")).isFalse();
        assertThat(WmsTxnId.isValid("W00000000000000I")).isFalse(); // I is not in Crockford Base32
    }
}
