package com.astrawms.masterdata.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GtinTest {

    @Test
    void acceptsValidGtins() {
        assertThat(Gtin.isValid("4006381333931")).isTrue();   // GTIN-13
        assertThat(Gtin.isValid("036000291452")).isTrue();    // GTIN-12 (UPC-A)
        assertThat(Gtin.isValid("96385074")).isTrue();        // GTIN-8
        assertThat(Gtin.isValid("10614141000415")).isTrue();  // GTIN-14 (GS1 example)
    }

    @Test
    void rejectsWrongCheckDigitLengthOrCharacters() {
        assertThat(Gtin.isValid("4006381333932")).isFalse();
        assertThat(Gtin.isValid("400638133393")).isFalse();   // 12 digits, wrong check digit
        assertThat(Gtin.isValid("40063813339")).isFalse();    // 11 digits
        assertThat(Gtin.isValid("40063A1333931")).isFalse();
        assertThat(Gtin.isValid(null)).isFalse();
    }
}
