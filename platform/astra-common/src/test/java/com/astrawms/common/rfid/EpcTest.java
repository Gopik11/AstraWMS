package com.astrawms.common.rfid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.astrawms.common.barcode.Gs1;
import org.junit.jupiter.api.Test;

class EpcTest {

    /** GS1 EPC Tag Data Standard, SGTIN-96 example: GTIN 80614141123458, serial 6789, filter 3. */
    private static final String SGTIN_HEX = "3074257BF7194E4000001A85";

    @Test
    void decodesSgtin96() {
        Epc.Tag t = Epc.parse(SGTIN_HEX).orElseThrow();
        assertThat(t.scheme()).isEqualTo(Epc.Scheme.SGTIN);
        assertThat(t.filter()).isEqualTo(3);
        assertThat(t.companyPrefix()).isEqualTo("0614141");
        assertThat(t.gtin()).isEqualTo("80614141123458");
        assertThat(t.serial()).isEqualTo("6789");
        assertThat(t.uri()).isEqualTo("urn:epc:id:sgtin:0614141.812345.6789");
        assertThat(t.hex()).isEqualTo(SGTIN_HEX);
    }

    @Test
    void readerFormatsAreNormalised() {
        assertThat(Epc.parse("0x" + SGTIN_HEX.toLowerCase())).map(Epc.Tag::gtin).hasValue("80614141123458");
        assertThat(Epc.parse("3074 257B F719 4E40 0000 1A85")).map(Epc.Tag::serial).hasValue("6789");
        // EPC bank read longer than the EPC: zero padding is accepted, other data is not.
        assertThat(Epc.parse(SGTIN_HEX + "0000")).map(Epc.Tag::hex).hasValue(SGTIN_HEX);
        assertThat(Epc.parse(SGTIN_HEX + "00A0")).isEmpty();
    }

    @Test
    void urisDecodeToTheSameTag() {
        assertThat(Epc.parse("urn:epc:tag:sgtin-96:3.0614141.812345.6789")).map(Epc.Tag::hex).hasValue(SGTIN_HEX);
        Epc.Tag pure = Epc.parse("urn:epc:id:sgtin:0614141.812345.6789").orElseThrow();
        assertThat(pure.gtin()).isEqualTo("80614141123458");
        assertThat(pure.filter()).isZero();
    }

    @Test
    void ssccRoundTrip() {
        // TDS example: urn:epc:tag:sscc-96:3.0614141.1234567890
        Epc.Tag t = Epc.parse("urn:epc:tag:sscc-96:3.0614141.1234567890").orElseThrow();
        assertThat(t.sscc()).isEqualTo("106141412345678908");
        assertThat(t.hex()).isEqualTo("3174257BF4499602D2000000");
        Epc.Tag back = Epc.parse(t.hex()).orElseThrow();
        assertThat(back.scheme()).isEqualTo(Epc.Scheme.SSCC);
        assertThat(back.sscc()).isEqualTo("106141412345678908");
        assertThat(back.uri()).isEqualTo("urn:epc:id:sscc:0614141.1234567890");
    }

    @Test
    void encodesForCommissioning() {
        assertThat(Epc.sgtin96("80614141123458", 7, 6789, 3).hex()).isEqualTo(SGTIN_HEX);
        assertThat(Epc.sgtin96("0614141123452", 7, 1, 1).gtin()).isEqualTo("00614141123452");
        assertThat(Epc.sscc96("106141412345678908", 7, 3).hex()).isEqualTo("3174257BF4499602D2000000");
        assertThatThrownBy(() -> Epc.sgtin96("80614141123459", 7, 1, 1)).hasMessageContaining("check digit");
        assertThatThrownBy(() -> Epc.sgtin96("80614141123458", 7, Epc.SGTIN_96_MAX_SERIAL + 1, 1))
                .hasMessageContaining("serial");
        assertThatThrownBy(() -> Epc.sscc96("106141412345678909", 7, 0)).hasMessageContaining("check digit");
        assertThatThrownBy(() -> Epc.sgtin96("80614141123458", 13, 1, 1)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void otherSchemes() {
        Epc.Tag sgln = Epc.parse("urn:epc:tag:sgln-96:3.0614141.12345.400").orElseThrow();
        assertThat(sgln.gln()).isEqualTo("0614141123452");
        assertThat(Epc.parse(sgln.hex())).map(Epc.Tag::uri).hasValue("urn:epc:id:sgln:0614141.12345.400");
        Epc.Tag grai = Epc.parse("urn:epc:tag:grai-96:3.0614141.12345.5678").orElseThrow();
        assertThat(Epc.parse(grai.hex())).map(Epc.Tag::scheme).hasValue(Epc.Scheme.GRAI);
        assertThat(grai.toGs1()).isEmpty();
        // GID-96: header 35, manager 1, class 2, serial 3
        assertThat(Epc.parse("350000001000002000000003")).map(Epc.Tag::uri).hasValue("urn:epc:id:gid:1.2.3");
    }

    @Test
    void rejectsWhatIsNotAnEpc() {
        assertThat(Epc.parse("SKU-100")).isEmpty();
        assertThat(Epc.parse("4006381333931")).isEmpty();            // a bare GTIN
        assertThat(Epc.parse("E28011606000020000000001")).isEmpty(); // a TID / vendor EPC: not a GS1 scheme
        assertThat(Epc.parse("3077257BF7194E4000001A85")).isEmpty(); // partition 7
        assertThat(Epc.parse("urn:epc:id:sgtin:0614141.81234.6789")).isEmpty(); // wrong item reference length
        assertThat(Epc.parse("urn:epc:id:sgtin:0614141.812345.06789")).isEmpty(); // 96-bit serial with leading zero
        assertThat(Epc.looksLikeEpc("E28011606000020000000001")).isTrue();
        assertThat(Epc.looksLikeEpc("LPN-0001")).isFalse();
    }

    @Test
    void rfidReadsStandInForGs1Scans() {
        Gs1.Data unit = Gs1.parse(SGTIN_HEX).orElseThrow();
        assertThat(unit.gtin()).isEqualTo("80614141123458");
        assertThat(unit.serial()).isEqualTo("6789");
        assertThat(Gs1.parse("3174257BF4499602D2000000")).map(Gs1.Data::sscc).hasValue("106141412345678908");
        // element strings are parsed as before
        assertThat(Gs1.parse("(01)09506000134352(10)ABC")).map(Gs1.Data::lot).hasValue("ABC");
        assertThat(Gs1.parse("SKU-100")).isEmpty();
    }
}
