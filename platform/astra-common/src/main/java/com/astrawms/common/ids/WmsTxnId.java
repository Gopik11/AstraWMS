package com.astrawms.common.ids;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * Generates WMS transaction IDs: 16 characters, time-ordered, Crockford Base32.
 *
 * <p>16 characters is the hard limit from ISD-00 §3.2, because the ID is written to SAP {@code XBLNR} /
 * {@code REF_DOC_NO} and Oracle transaction references for ERP-side duplicate checks (INT-014).
 * Layout: prefix {@code W} + 9 chars of epoch milliseconds (45 bits, valid until year 3084) + 6 chars (30 bits).
 * The 30-bit part is random for the first ID in a millisecond and incremented for further IDs in the same
 * millisecond (monotonic, as in ULID), so IDs from one process never collide and always sort in creation order.
 * Across processes a collision requires the same millisecond and the same 30-bit value; the database unique
 * constraint on {@code wms_txn_id} is the final guard.
 */
public final class WmsTxnId {

    public static final int LENGTH = 16;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int RANDOM_BITS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static long lastMillis = -1;
    private static int lastRandom;

    private WmsTxnId() {
    }

    public static String next() {
        return next(Clock.systemUTC());
    }

    public static String next(Clock clock) {
        long millis;
        int random;
        synchronized (WmsTxnId.class) {
            millis = Math.max(clock.millis(), lastMillis);
            if (millis == lastMillis) {
                lastRandom++;
                if (lastRandom >= (1 << RANDOM_BITS)) { // 2^30 IDs in one millisecond: borrow the next one
                    millis++;
                    lastRandom = RANDOM.nextInt(1 << (RANDOM_BITS - 1));
                }
            } else {
                lastRandom = RANDOM.nextInt(1 << (RANDOM_BITS - 1)); // leave headroom for increments
            }
            lastMillis = millis;
            random = lastRandom;
        }
        char[] out = new char[LENGTH];
        out[0] = 'W';
        for (int i = 9; i >= 1; i--) {
            out[i] = ALPHABET.charAt((int) (millis & 31));
            millis >>>= 5;
        }
        for (int i = 15; i >= 10; i--) {
            out[i] = ALPHABET.charAt(random & 31);
            random >>>= 5;
        }
        return new String(out);
    }

    public static boolean isValid(String id) {
        if (id == null || id.length() != LENGTH || id.charAt(0) != 'W') {
            return false;
        }
        for (int i = 1; i < LENGTH; i++) {
            if (ALPHABET.indexOf(id.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
