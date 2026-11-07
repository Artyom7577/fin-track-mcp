package com.artyomh.finance.application.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The one place percentages are computed, so every tool rounds the same way.
 */
public final class Percent {

    private static final int SCALE = 1;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Percent() {
    }

    public static BigDecimal of(BigDecimal part, BigDecimal whole) {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(whole, "whole");
        if (whole.signum() == 0) {
            throw new IllegalArgumentException("percentage of zero is undefined");
        }
        return part.multiply(HUNDRED).divide(whole, SCALE, RoundingMode.HALF_EVEN);
    }
}
