package com.astrawms.common.barcode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GS1-128 / GS1 DataMatrix element strings (GS1 General Specifications §3, §7.8), as handhelds deliver them:
 * <ul>
 *   <li>human-readable, with application identifiers in parentheses: {@code (01)09506000134352(10)ABC(17)271231};</li>
 *   <li>raw, as a keyboard-wedge scanner sends them: optional symbology identifier ({@code ]C1}, {@code ]d2},
 *       {@code ]Q3}), AIs run together, variable-length fields ended by the group separator (ASCII 29, FNC1).</li>
 * </ul>
 * Supported AIs: 00 SSCC, 01 GTIN, 02 GTIN of contained items, 10 batch/lot, 11 production date, 15 best before,
 * 17 expiry, 21 serial, 30 variable count, 37 count of units, 400 customer PO. A plain item number or a bare GTIN is
 * not GS1 element-string data: {@link #parse} returns empty and the scan is used as it is.
 */
public final class Gs1 {

    public static final char GS = '\u001D';

    /** Fixed data lengths (without the AI) of the supported fixed-length AIs. */
    private static final Map<String, Integer> FIXED = Map.of("00", 18, "01", 14, "02", 14, "11", 6, "13", 6, "15", 6,
            "17", 6);
    /** Maximum data lengths of the supported variable-length AIs. */
    private static final Map<String, Integer> VARIABLE = Map.of("10", 20, "21", 20, "30", 8, "37", 8, "400", 30);

    /** What a GS1 scan says. Absent fields are null. */
    public record Data(String sscc, String gtin, String contentGtin, String lot, LocalDate productionDate,
                       LocalDate bestBefore, LocalDate expiry, String serial, BigDecimal count, String customerPo,
                       Map<String, String> elements) {

        /** The GTIN the scan identifies an item by: AI 01, else AI 02 (the trade items inside a logistic unit). */
        public String itemGtin() {
            return gtin != null ? gtin : contentGtin;
        }
    }

    private Gs1() {
    }

    /** Parses a scan; empty when it is not a GS1 element string (or is malformed). */
    public static Optional<Data> parse(String scan) {
        if (scan == null) {
            return Optional.empty();
        }
        String s = scan.strip();
        if (s.startsWith("]")) {                         // symbology identifier: ]C1 GS1-128, ]d2 DataMatrix, ]Q3 QR
            if (s.length() < 3) {
                return Optional.empty();
            }
            s = s.substring(3);
        }
        if (s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8)) {
            s = digitalLink(s);                          // a QR code with a GS1 Digital Link URL (ADR-0025)
            if (s == null) {
                return Optional.empty();
            }
        }
        try {
            Map<String, String> elements = s.startsWith("(") ? parseBracketed(s) : parseRaw(s);
            if (elements == null || elements.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Data(elements.get("00"), elements.get("01"), elements.get("02"), elements.get("10"),
                    date(elements.get("11")), date(elements.get("15")), date(elements.get("17")), elements.get("21"),
                    count(elements.get("37") != null ? elements.get("37") : elements.get("30")), elements.get("400"),
                    Map.copyOf(elements)));
        } catch (RuntimeException e) {                   // malformed field, impossible date: not usable GS1 data
            return Optional.empty();
        }
    }

    private static final java.util.Set<String> LINK_AIS = java.util.Set.of("00", "01", "02", "10", "11", "13", "15", "17",
            "21", "30", "37", "400");

    /**
     * A GS1 Digital Link ({@code https://id.example.com/01/09506000134352/10/LOT7?17=270630}) as a bracketed element
     * string; null when the URL carries no GTIN or SSCC. AIs AstraWMS does not use are ignored.
     */
    static String digitalLink(String url) {
        java.net.URI u;
        try {
            u = java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] parts = u.getRawPath() == null ? new String[0] : u.getRawPath().split("/");
        StringBuilder out = new StringBuilder();
        int start = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("01") || parts[i].equals("00")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        for (int i = start; i + 1 < parts.length; i += 2) {
            if (LINK_AIS.contains(parts[i])) {
                out.append('(').append(parts[i]).append(')')
                        .append(java.net.URLDecoder.decode(parts[i + 1], java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        if (u.getRawQuery() != null) {
            for (String kv : u.getRawQuery().split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && LINK_AIS.contains(kv.substring(0, eq))) {
                    out.append('(').append(kv, 0, eq).append(')')
                            .append(java.net.URLDecoder.decode(kv.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static Map<String, String> parseBracketed(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) != '(') {
                throw new IllegalArgumentException("expected (");
            }
            int close = s.indexOf(')', i);
            if (close < 0) {
                throw new IllegalArgumentException("unclosed AI");
            }
            String ai = s.substring(i + 1, close);
            int next = s.indexOf('(', close);
            String value = s.substring(close + 1, next < 0 ? s.length() : next).replace(String.valueOf(GS), "");
            check(ai, value);
            out.put(ai, value);
            i = next < 0 ? s.length() : next;
        }
        return out;
    }

    /**
     * Raw element string. Only strings that start with a known AI and parse completely are accepted, so an ordinary
     * item number such as "0100-ABC" is not taken for GS1 data. A bare 14-digit GTIN is left to the caller.
     */
    private static Map<String, String> parseRaw(String s) {
        if (s.length() <= 14 && s.chars().allMatch(Character::isDigit)) {
            return null;                                 // a bare GTIN-8/12/13/14, not an element string
        }
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == GS) {
                i++;
                continue;
            }
            String ai = ai(s, i);
            if (ai == null) {
                throw new IllegalArgumentException("unknown AI");
            }
            i += ai.length();
            String value;
            Integer fixed = FIXED.get(ai);
            if (fixed != null) {
                if (i + fixed > s.length()) {
                    throw new IllegalArgumentException("short field");
                }
                value = s.substring(i, i + fixed);
                i += fixed;
            } else {
                int end = s.indexOf(GS, i);
                end = end < 0 ? s.length() : end;
                value = s.substring(i, end);
                i = end;
            }
            check(ai, value);
            out.put(ai, value);
        }
        return out;
    }

    private static String ai(String s, int i) {
        for (int len = 2; len <= 3 && i + len <= s.length(); len++) {
            String ai = s.substring(i, i + len);
            if (FIXED.containsKey(ai) || VARIABLE.containsKey(ai)) {
                return ai;
            }
        }
        return null;
    }

    private static void check(String ai, String value) {
        Integer fixed = FIXED.get(ai);
        Integer max = VARIABLE.get(ai);
        if (fixed == null && max == null) {
            throw new IllegalArgumentException("unsupported AI " + ai);
        }
        if (fixed != null && (value.length() != fixed || !value.chars().allMatch(Character::isDigit))) {
            throw new IllegalArgumentException("AI " + ai + " needs " + fixed + " digits");
        }
        if (max != null && (value.isEmpty() || value.length() > max)) {
            throw new IllegalArgumentException("AI " + ai + " length");
        }
        if (("00".equals(ai) || "01".equals(ai) || "02".equals(ai)) && !checkDigitOk(value)) {
            throw new IllegalArgumentException("AI " + ai + " check digit");
        }
        if (("30".equals(ai) || "37".equals(ai)) && !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("AI " + ai + " must be numeric");
        }
    }

    /** GS1 mod-10 check digit over a numeric key (GTIN, SSCC). */
    public static boolean checkDigitOk(String digits) {
        int sum = 0;
        int n = digits.length();
        for (int k = 0; k < n - 1; k++) {
            int d = digits.charAt(n - 2 - k) - '0';
            sum += k % 2 == 0 ? d * 3 : d;
        }
        return (10 - sum % 10) % 10 == digits.charAt(n - 1) - '0';
    }

    /** YYMMDD; DD 00 means the last day of the month (§7.12); the century follows the GS1 sliding window. */
    private static LocalDate date(String v) {
        if (v == null) {
            return null;
        }
        int yy = Integer.parseInt(v.substring(0, 2));
        int mm = Integer.parseInt(v.substring(2, 4));
        int dd = Integer.parseInt(v.substring(4, 6));
        int current = LocalDate.now().getYear();
        int century = current / 100 * 100;
        int year = century + yy;
        int diff = yy - current % 100;
        if (diff >= 51) {
            year -= 100;
        } else if (diff <= -50) {
            year += 100;
        }
        if (mm < 1 || mm > 12) {
            throw new IllegalArgumentException("month");
        }
        return dd == 0 ? YearMonth.of(year, mm).atEndOfMonth() : LocalDate.of(year, mm, dd);
    }

    private static BigDecimal count(String v) {
        return v == null ? null : new BigDecimal(v);
    }
}
