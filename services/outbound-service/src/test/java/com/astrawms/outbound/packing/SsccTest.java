package com.astrawms.outbound.packing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SsccTest {

    @Test
    void buildsAnEighteenDigitSsccWithGs1CheckDigit() {
        assertThat(PackingService.sscc("0614141", 1)).isEqualTo("006141410000000012");
        assertThat(PackingService.sscc("0614141", 123456789)).hasSize(18).startsWith("00614141");
    }
}
