package com.astrawms.common.rfid;

import com.astrawms.common.barcode.Gs1;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * EPC (Electronic Product Code) of a UHF RFID tag, per the GS1 EPC Tag Data Standard (TDS 2.x), as RFID readers
 * deliver it (ADR-0027):
 * <ul>
 *   <li>the EPC bank as hex, e.g. {@code 3074257BF7194E4000001A85} (optionally {@code 0x}-prefixed, with spaces or
 *       dashes; a longer read is accepted when the bits after the 96-bit EPC are zero padding);</li>
 *   <li>a pure identity URI ({@code urn:epc:id:sgtin:0614141.812345.6789}) or a tag URI
 *       ({@code urn:epc:tag:sgtin-96:3.0614141.812345.6789}), as middleware and EPCIS events carry it.</li>
 * </ul>
 * Decoded schemes: SGTIN-96 (trade item + serial), SSCC-96 (logistic unit / LPN), SGLN-96 (location), GRAI-96
 * (returnable asset), GIAI-96 (individual asset) and GID-96. SGTIN-96 and SSCC-96 can also be encoded, so a handheld can
 * write (commission) a tag for an item serial or a pallet.
 */
public final class Epc {

    public enum Scheme { SGTIN, SSCC, SGLN, GRAI, GIAI, GID }

    /**
     * A decoded EPC. {@code hex} is the canonical 96-bit EPC (24 upper-case hex digits); GS1 keys carry their check
     * digit: {@code gtin} is a GTIN-14, {@code sscc} an SSCC-18, {@code gln} a GLN-13. Absent fields are null.
     */
    public record Tag(Scheme scheme, String hex, int filter, String companyPrefix, String gtin, String serial,
                      String sscc, String gln, String extension, String assetType, String uri) {

        /** The GS1 element string view of the tag, so an RFID read can stand in for a GS1-128 scan. */
        public Optional<Gs1.Data> toGs1() {
            return switch (scheme) {
                case SGTIN -> Optional.of(new Gs1.Data(null, gtin, null, null, null, null, null, serial, null, null,
                        Map.of("01", gtin, "21", serial)));
                case SSCC -> Optional.of(new Gs1.Data(sscc, null, null, null, null, null, null, null, null, null,
                        Map.of("00", sscc)));
                default -> Optional.empty();
            };
        }
    }

    private static final int SGTIN_96 = 0x30;
    private static final int SSCC_96 = 0x31;
    private static final int SGLN_96 = 0x32;
    private static final int GRAI_96 = 0x33;
    private static final int GIAI_96 = 0x34;
    private static final int GID_96 = 0x35;

    /** Partition tables: {company prefix bits, company prefix digits, reference bits, reference digits}. */
    private static final int[][] SGTIN_PARTITIONS = {{40, 12, 4, 1}, {37, 11, 7, 2}, {34, 10, 10, 3}, {30, 9, 14, 4},
            {27, 8, 17, 5}, {24, 7, 20, 6}, {20, 6, 24, 7}};
    private static final int[][] SSCC_PARTITIONS = {{40, 12, 18, 5}, {37, 11, 21, 6}, {34, 10, 24, 7}, {30, 9, 28, 8},
            {27, 8, 31, 9}, {24, 7, 34, 10}, {20, 6, 38, 11}};
    private static final int[][] SGLN_PARTITIONS = {{40, 12, 1, 0}, {37, 11, 4, 1}, {34, 10, 7, 2}, {30, 9, 11, 3},
            {27, 8, 14, 4}, {24, 7, 17, 5}, {20, 6, 21, 6}};
    private static final int[][] GRAI_PARTITIONS = {{40, 12, 4, 0}, {37, 11, 7, 1}, {34, 10, 10, 2}, {30, 9, 14, 3},
            {27, 8, 17, 4}, {24, 7, 20, 5}, {20, 6, 24, 6}};
    private static final int[][] GIAI_PARTITIONS = {{40, 12, 42, 12}, {37, 11, 45, 13}, {34, 10, 48, 14},
            {30, 9, 52, 15}, {27, 8, 55, 16}, {24, 7, 58, 17}, {20, 6, 62, 18}};

    /** Largest serial an SGTIN-96 can hold (38 bits). */
    public static final long SGTIN_96_MAX_SERIAL = (1L << 38) - 1;

    private Epc() {
    }

    /** Whether a scan looks like an EPC (hex EPC bank or EPC URI), without decoding it. */
    public static boolean looksLikeEpc(String scan) {
        if (scan == null) {
            return false;
        }
        String s = scan.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("urn:epc:id:") || s.startsWith("urn:epc:tag:")) {
            return true;
        }
        String hex = hexOf(scan);
        return hex != null && hex.length() >= 24 && hex.length() % 4 == 0;
    }

    /** Decodes a read; empty when it is not a supported, well-formed EPC. */
    public static Optional<Tag> parse(String read) {
        if (read == null || read.isBlank()) {
            return Optional.empty();
        }
        try {
            String s = read.strip();
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith("urn:epc:id:")) {
                return Optional.of(fromPureIdentity(s.substring("urn:epc:id:".length()), 0));
            }
            if (lower.startsWith("urn:epc:tag:")) {
                return Optional.of(fromTagUri(s.substring("urn:epc:tag:".length())));
            }
            String hex = hexOf(s);
            if (hex == null || hex.length() < 24 || hex.length() % 4 != 0) {
                return Optional.empty();
            }
            if (hex.length() > 24 && !hex.substring(24).chars().allMatch(c -> c == '0')) {
                return Optional.empty();                    // a longer scheme (SGTIN-198, ...): not supported
            }
            return Optional.of(decode(hex.substring(0, 24)));
        } catch (RuntimeException e) {                       // malformed, wrong partition, value out of range
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ decoding (binary)

    private static Tag decode(String hex) {
        BigInteger v = new BigInteger(hex, 16);
        Bits b = new Bits(v, 96);
        int header = (int) b.take(8);
        return switch (header) {
            case SGTIN_96 -> {
                int filter = (int) b.take(3);
                int[] p = SGTIN_PARTITIONS[partition(b)];
                String company = digits(b.take(p[0]), p[1]);
                String itemRef = digits(b.take(p[2]), p[3]);
                long serial = b.take(38);
                yield sgtin(filter, company, itemRef, Long.toString(serial));
            }
            case SSCC_96 -> {
                int filter = (int) b.take(3);
                int[] p = SSCC_PARTITIONS[partition(b)];
                String company = digits(b.take(p[0]), p[1]);
                String serialRef = digits(b.take(p[2]), p[3]);
                if (b.take(24) != 0) {
                    throw new IllegalArgumentException("SSCC-96 reserved bits");
                }
                yield sscc(filter, company, serialRef);
            }
            case SGLN_96 -> {
                int filter = (int) b.take(3);
                int[] p = SGLN_PARTITIONS[partition(b)];
                String company = digits(b.take(p[0]), p[1]);
                String locRef = p[3] == 0 ? zeroRef(b.take(p[2])) : digits(b.take(p[2]), p[3]);
                long ext = b.take(41);
                yield sgln(filter, company, locRef, Long.toString(ext));
            }
            case GRAI_96 -> {
                int filter = (int) b.take(3);
                int[] p = GRAI_PARTITIONS[partition(b)];
                String company = digits(b.take(p[0]), p[1]);
                String assetType = p[3] == 0 ? zeroRef(b.take(p[2])) : digits(b.take(p[2]), p[3]);
                long serial = b.take(38);
                yield grai(filter, company, assetType, Long.toString(serial));
            }
            case GIAI_96 -> {
                int filter = (int) b.take(3);
                int[] p = GIAI_PARTITIONS[partition(b)];
                String company = digits(b.take(p[0]), p[1]);
                BigInteger asset = b.takeBig(p[2]);
                if (asset.toString().length() > p[3]) {
                    throw new IllegalArgumentException("GIAI-96 asset reference too long");
                }
                yield new Tag(Scheme.GIAI, hex.toUpperCase(Locale.ROOT), filter, company, null, asset.toString(), null,
                        null, null, null, "urn:epc:id:giai:" + company + "." + asset);
            }
            case GID_96 -> {
                long manager = b.take(28);
                long objectClass = b.take(24);
                long serial = b.take(36);
                yield new Tag(Scheme.GID, hex.toUpperCase(Locale.ROOT), 0, null, null, Long.toString(serial), null,
                        null, null, Long.toString(objectClass), "urn:epc:id:gid:" + manager + "." + objectClass + "." + serial);
            }
            default -> throw new IllegalArgumentException("unsupported EPC header " + header);
        };
    }

    private static int partition(Bits b) {
        int p = (int) b.take(3);
        if (p > 6) {
            throw new IllegalArgumentException("partition " + p);
        }
        return p;
    }

    private static String zeroRef(long value) {
        if (value != 0) {
            throw new IllegalArgumentException("non-zero empty reference");
        }
        return "";
    }

    // ------------------------------------------------------------------ building tags (shared by all inputs)

    private static Tag sgtin(int filter, String company, String itemRef, String serial) {
        String body = itemRef.charAt(0) + company + itemRef.substring(1);
        String gtin = body + checkDigit(body);
        String hex = encodeSgtin(filter, company, itemRef, Long.parseLong(serial));
        return new Tag(Scheme.SGTIN, hex, filter, company, gtin, serial, null, null, null, null,
                "urn:epc:id:sgtin:" + company + "." + itemRef + "." + serial);
    }

    private static Tag sscc(int filter, String company, String serialRef) {
        String body = serialRef.charAt(0) + company + serialRef.substring(1);
        String sscc = body + checkDigit(body);
        String hex = encodeSscc(filter, company, serialRef);
        return new Tag(Scheme.SSCC, hex, filter, company, null, null, sscc, null, null, null,
                "urn:epc:id:sscc:" + company + "." + serialRef);
    }

    private static Tag sgln(int filter, String company, String locRef, String ext) {
        String body = company + locRef;
        String gln = body + checkDigit(body);
        int[] p = SGLN_PARTITIONS[partitionFor(SGLN_PARTITIONS, company)];
        Bits out = new Bits();
        out.put(SGLN_96, 8).put(filter, 3).put(partitionFor(SGLN_PARTITIONS, company), 3)
                .put(Long.parseLong(company), p[0]).put(locRef.isEmpty() ? 0 : Long.parseLong(locRef), p[2])
                .put(Long.parseLong(ext), 41);
        return new Tag(Scheme.SGLN, out.hex(), filter, company, null, null, null, gln, ext, null,
                "urn:epc:id:sgln:" + company + "." + locRef + "." + ext);
    }

    private static Tag grai(int filter, String company, String assetType, String serial) {
        int[] p = GRAI_PARTITIONS[partitionFor(GRAI_PARTITIONS, company)];
        Bits out = new Bits();
        out.put(GRAI_96, 8).put(filter, 3).put(partitionFor(GRAI_PARTITIONS, company), 3)
                .put(Long.parseLong(company), p[0]).put(assetType.isEmpty() ? 0 : Long.parseLong(assetType), p[2])
                .put(Long.parseLong(serial), 38);
        return new Tag(Scheme.GRAI, out.hex(), filter, company, null, serial, null, null, null, assetType,
                "urn:epc:id:grai:" + company + "." + assetType + "." + serial);
    }

    // ------------------------------------------------------------------ URIs

    private static Tag fromPureIdentity(String rest, int filter) {
        int colon = rest.indexOf(':');
        String scheme = rest.substring(0, colon).toLowerCase(Locale.ROOT);
        String[] f = rest.substring(colon + 1).split("\\.", -1);
        return switch (scheme) {
            case "sgtin" -> {
                need(f, 3);
                checkSplit(SGTIN_PARTITIONS, f[0], f[1]);
                yield sgtin(filter, f[0], f[1], numericSerial(f[2]));
            }
            case "sscc" -> {
                need(f, 2);
                checkSplit(SSCC_PARTITIONS, f[0], f[1]);
                yield sscc(filter, f[0], f[1]);
            }
            case "sgln" -> {
                need(f, 3);
                checkSplit(SGLN_PARTITIONS, f[0], f[1]);
                yield sgln(filter, f[0], f[1], numericSerial(f[2]));
            }
            case "grai" -> {
                need(f, 3);
                checkSplit(GRAI_PARTITIONS, f[0], f[1]);
                yield grai(filter, f[0], f[1], numericSerial(f[2]));
            }
            default -> throw new IllegalArgumentException("unsupported EPC URI scheme " + scheme);
        };
    }

    /** {@code sgtin-96:3.0614141.812345.6789}: the filter value comes first. */
    private static Tag fromTagUri(String rest) {
        int colon = rest.indexOf(':');
        String scheme = rest.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.endsWith("-96")) {
            throw new IllegalArgumentException("only 96-bit tag URIs are supported");
        }
        String body = rest.substring(colon + 1);
        int dot = body.indexOf('.');
        int filter = Integer.parseInt(body.substring(0, dot));
        if (filter < 0 || filter > 7) {
            throw new IllegalArgumentException("filter " + filter);
        }
        return fromPureIdentity(scheme.substring(0, scheme.length() - 3) + ":" + body.substring(dot + 1), filter);
    }

    private static void need(String[] f, int n) {
        if (f.length != n) {
            throw new IllegalArgumentException("expected " + n + " fields");
        }
        for (String part : f) {
            if (!part.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("EPC URI fields must be numeric");
            }
        }
    }

    private static String numericSerial(String s) {
        if (s.isEmpty() || (s.length() > 1 && s.charAt(0) == '0')) {
            throw new IllegalArgumentException("a 96-bit serial is a number without leading zeros");
        }
        return s;
    }

    private static void checkSplit(int[][] table, String company, String reference) {
        int[] p = table[partitionFor(table, company)];
        if (reference.length() != p[3]) {
            throw new IllegalArgumentException("reference must have " + p[3] + " digits");
        }
    }

    private static int partitionFor(int[][] table, String company) {
        for (int i = 0; i < table.length; i++) {
            if (table[i][1] == company.length()) {
                return i;
            }
        }
        throw new IllegalArgumentException("GS1 company prefix must have 6-12 digits");
    }

    // ------------------------------------------------------------------ encoding

    /**
     * SGTIN-96 for a GTIN (8-14 digits, check digit included) and a numeric serial. The company prefix length is not
     * in the GTIN, so the caller supplies it (6-12 digits, from the GS1 member organisation / the item master).
     */
    public static Tag sgtin96(String gtin, int companyPrefixLength, long serial, int filter) {
        String g14 = padGtin(gtin);
        if (!Gs1.checkDigitOk(g14)) {
            throw new IllegalArgumentException("GTIN " + gtin + " has an invalid check digit");
        }
        if (serial < 0 || serial > SGTIN_96_MAX_SERIAL) {
            throw new IllegalArgumentException("SGTIN-96 serial must be 0.." + SGTIN_96_MAX_SERIAL);
        }
        checkFilter(filter);
        String company = g14.substring(1, 1 + companyPrefixLength);
        String itemRef = g14.charAt(0) + g14.substring(1 + companyPrefixLength, 13);
        partitionFor(SGTIN_PARTITIONS, company);
        return sgtin(filter, company, itemRef, Long.toString(serial));
    }

    /** SSCC-96 for an 18-digit SSCC (check digit included). */
    public static Tag sscc96(String sscc, int companyPrefixLength, int filter) {
        if (sscc == null || !sscc.matches("\\d{18}") || !Gs1.checkDigitOk(sscc)) {
            throw new IllegalArgumentException("SSCC must be 18 digits with a valid check digit");
        }
        checkFilter(filter);
        String company = sscc.substring(1, 1 + companyPrefixLength);
        String serialRef = sscc.charAt(0) + sscc.substring(1 + companyPrefixLength, 17);
        partitionFor(SSCC_PARTITIONS, company);
        return sscc(filter, company, serialRef);
    }

    private static String encodeSgtin(int filter, String company, String itemRef, long serial) {
        int part = partitionFor(SGTIN_PARTITIONS, company);
        int[] p = SGTIN_PARTITIONS[part];
        if (itemRef.length() != p[3]) {
            throw new IllegalArgumentException("item reference must have " + p[3] + " digits");
        }
        if (serial < 0 || serial > SGTIN_96_MAX_SERIAL) {
            throw new IllegalArgumentException("SGTIN-96 serial out of range");
        }
        return new Bits().put(SGTIN_96, 8).put(filter, 3).put(part, 3).put(Long.parseLong(company), p[0])
                .put(Long.parseLong(itemRef), p[2]).put(serial, 38).hex();
    }

    private static String encodeSscc(int filter, String company, String serialRef) {
        int part = partitionFor(SSCC_PARTITIONS, company);
        int[] p = SSCC_PARTITIONS[part];
        if (serialRef.length() != p[3]) {
            throw new IllegalArgumentException("serial reference must have " + p[3] + " digits");
        }
        return new Bits().put(SSCC_96, 8).put(filter, 3).put(part, 3).put(Long.parseLong(company), p[0])
                .put(Long.parseLong(serialRef), p[2]).put(0, 24).hex();
    }

    private static void checkFilter(int filter) {
        if (filter < 0 || filter > 7) {
            throw new IllegalArgumentException("filter must be 0..7");
        }
    }

    private static String padGtin(String gtin) {
        if (gtin == null || !gtin.matches("\\d{8}|\\d{12,14}")) {
            throw new IllegalArgumentException("GTIN must be 8, 12, 13 or 14 digits");
        }
        return "0".repeat(14 - gtin.length()) + gtin;
    }

    // ------------------------------------------------------------------ helpers

    /** The hex digits of a read (no 0x, spaces, dashes or colons), upper case; null when it is not hex. */
    public static String hexOf(String read) {
        String s = read.strip();
        if (s.regionMatches(true, 0, "0x", 0, 2)) {
            s = s.substring(2);
        }
        s = s.replaceAll("[\\s:-]", "");
        if (s.isEmpty() || !s.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return null;
        }
        return s.toUpperCase(Locale.ROOT);
    }

    private static String digits(long value, int n) {
        String s = Long.toString(value);
        if (s.length() > n) {
            throw new IllegalArgumentException("value " + value + " exceeds " + n + " digits");
        }
        return "0".repeat(n - s.length()) + s;
    }

    /** GS1 mod-10 check digit for the digits before it. */
    static int checkDigit(String body) {
        int sum = 0;
        for (int k = 0; k < body.length(); k++) {
            int d = body.charAt(body.length() - 1 - k) - '0';
            sum += k % 2 == 0 ? d * 3 : d;
        }
        return (10 - sum % 10) % 10;
    }

    /** A big-endian bit cursor over a 96-bit value, or a 96-bit builder. */
    private static final class Bits {
        private BigInteger value;
        private int remaining;
        private int written;

        Bits(BigInteger value, int length) {
            this.value = value;
            this.remaining = length;
        }

        Bits() {
            this.value = BigInteger.ZERO;
        }

        long take(int n) {
            return takeBig(n).longValueExact();
        }

        BigInteger takeBig(int n) {
            remaining -= n;
            BigInteger out = value.shiftRight(remaining).and(BigInteger.ONE.shiftLeft(n).subtract(BigInteger.ONE));
            return out;
        }

        Bits put(long v, int n) {
            if (v < 0 || (n < 63 && v >= (1L << n))) {
                throw new IllegalArgumentException("value " + v + " does not fit " + n + " bits");
            }
            value = value.shiftLeft(n).or(BigInteger.valueOf(v));
            written += n;
            return this;
        }

        String hex() {
            if (written != 96) {
                throw new IllegalStateException("EPC is " + written + " bits");
            }
            String h = value.toString(16).toUpperCase(Locale.ROOT);
            return "0".repeat(24 - h.length()) + h;
        }
    }
}
