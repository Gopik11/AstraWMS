package com.astrawms.masterdata.service;

/** GS1 GTIN-8/12/13/14 check digit validation (mod-10, weights 3/1 from the right). */
public final class Gtin {

    private Gtin() {
    }

    public static boolean isValid(String gtin) {
        if (gtin == null) {
            return false;
        }
        int len = gtin.length();
        if (len != 8 && len != 12 && len != 13 && len != 14) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < len - 1; i++) {
            char c = gtin.charAt(len - 2 - i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * (i % 2 == 0 ? 3 : 1);
        }
        char check = gtin.charAt(len - 1);
        return check >= '0' && check <= '9' && (10 - sum % 10) % 10 == check - '0';
    }
}
