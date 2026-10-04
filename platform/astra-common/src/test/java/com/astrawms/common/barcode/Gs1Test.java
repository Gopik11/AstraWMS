package com.astrawms.common.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class Gs1Test {

    @Test
    void qrDigitalLinkAndDataMatrixGiveTheSameData() {
        var link = Gs1.parse("]Q3https://id.gs1.org/01/09506000134352/10/LOT-7?17=270630&3103=000500&37=12").orElseThrow();
        var matrix = Gs1.parse("]d20109506000134352" + "10LOT-7" + Gs1.GS + "17270630" + "3712").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(link.gtin()).isEqualTo("09506000134352").isEqualTo(matrix.gtin());
        org.assertj.core.api.Assertions.assertThat(link.lot()).isEqualTo("LOT-7").isEqualTo(matrix.lot());
        org.assertj.core.api.Assertions.assertThat(link.expiry()).isEqualTo(matrix.expiry());
        org.assertj.core.api.Assertions.assertThat(link.count()).isEqualByComparingTo("12").isEqualByComparingTo(matrix.count());
        org.assertj.core.api.Assertions.assertThat(Gs1.parse("https://example.com/products/42")).isEmpty();
    }

    @Test
    void bracketedGtinLotExpiryAndSerial() {
        Gs1.Data d = Gs1.parse("(01)09506000134352(17)271231(10)LOT-7(21)SN123").orElseThrow();
        assertThat(d.gtin()).isEqualTo("09506000134352");
        assertThat(d.expiry()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(d.lot()).isEqualTo("LOT-7");
        assertThat(d.serial()).isEqualTo("SN123");
    }

    @Test
    void rawScanWithSymbologyIdAndGroupSeparators() {
        String raw = "]C10109506000134352" + "10LOT-7" + Gs1.GS + "17270600" + "3712" + Gs1.GS;
        Gs1.Data d = Gs1.parse(raw).orElseThrow();
        assertThat(d.gtin()).isEqualTo("09506000134352");
        assertThat(d.lot()).isEqualTo("LOT-7");
        assertThat(d.expiry()).isEqualTo(LocalDate.of(2027, 6, 30));          // DD 00 = last day of the month
        assertThat(d.count()).isEqualByComparingTo(BigDecimal.valueOf(12));
    }

    @Test
    void ssccOfAPalletWithItsContents() {
        Gs1.Data d = Gs1.parse("(00)106141410000000019(02)09506000134352(37)24").orElseThrow();
        assertThat(d.sscc()).isEqualTo("106141410000000019");
        assertThat(d.itemGtin()).isEqualTo("09506000134352");
        assertThat(d.count()).isEqualByComparingTo("24");
    }

    @Test
    void plainItemNumbersBareGtinsAndBadCheckDigitsAreNotGs1() {
        assertThat(Gs1.parse("SKU-1")).isEmpty();
        assertThat(Gs1.parse("09506000134352")).isEmpty();                   // bare GTIN: used as it is
        assertThat(Gs1.parse("(01)09506000134353")).isEmpty();               // wrong check digit
        assertThat(Gs1.parse("(17)271399")).isEmpty();                       // no month 13
        assertThat(Gs1.parse("0100-ABC")).isEmpty();
    }
}
