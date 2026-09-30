package com.astrawms.inventory.domain;

import java.math.BigDecimal;

/** Quantity helpers. Quantities are stored as numeric(18,3) and exposed without insignificant zeros. */
public final class Quantities {

    private Quantities() {
    }

    /** {@code 120.000 → 120}, {@code 1.500 → 1.5}, {@code null → null}. */
    public static BigDecimal normalize(BigDecimal qty) {
        if (qty == null) {
            return null;
        }
        BigDecimal stripped = qty.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
