package com.astrawms.outbound.packing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Boundary to the carriers / multi-carrier shipping system (§5.2 carrier integration): a label and tracking number
 * per carton. {@link Simulated} stands in for a real platform in development and test environments, like the
 * simulated SAP backend of the SAP adapter.
 */
public interface CarrierGateway {

    record Parcel(String carrierScac, String sscc, String orderRef, String shipToName, String shipToCity,
                  String shipToCountry, BigDecimal weightKg) {
    }

    record Label(String carrierScac, String trackingNo, String zpl) {
    }

    Label label(Parcel parcel);

    /** Simulated carrier: UPS-style tracking numbers for UPSN, carrier-prefixed numbers otherwise; ZPL label text. */
    class Simulated implements CarrierGateway {

        private final Clock clock;

        public Simulated(Clock clock) {
            this.clock = clock;
        }

        @Override
        public Label label(Parcel p) {
            String scac = p.carrierScac() == null || p.carrierScac().isBlank() ? "CUST" : p.carrierScac().toUpperCase(Locale.ROOT);
            long n = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
            String tracking = "UPSN".equals(scac) ? "1Z999AA1" + n : scac + n;
            LocalDate shipDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            String zpl = """
                    ^XA
                    ^CF0,40^FO40,40^FD%s^FS
                    ^CF0,28^FO40,100^FDSHIP TO: %s^FS
                    ^FO40,140^FD%s %s^FS
                    ^FO40,190^FDORDER %s   %s KG   %s^FS
                    ^FO40,250^BY3^BCN,120,Y,N,N^FD%s^FS
                    ^FO40,430^BY3^BCN,120,Y,N,N^FD(00)%s^FS
                    ^XZ""".formatted(scac, nz(p.shipToName()), nz(p.shipToCity()), nz(p.shipToCountry()), p.orderRef(),
                    p.weightKg() == null ? "-" : p.weightKg().stripTrailingZeros().toPlainString(), shipDate, tracking, p.sscc());
            return new Label(scac, tracking, zpl);
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }
}
