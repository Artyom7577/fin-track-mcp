package com.artyomh.finance.application.analysis.subscription;

import java.math.BigDecimal;
import java.util.Objects;

public record SubscriptionThresholds(int minMonths,
                                     BigDecimal maxVariationPct,
                                     int lookbackMonths,
                                     int maxMonthsSinceLast,
                                     BigDecimal maxChargesPerMonth) {

    private static final int MIN_RECURRING_MONTHS = 2;

    public SubscriptionThresholds {
        Objects.requireNonNull(maxVariationPct, "maxVariationPct");
        Objects.requireNonNull(maxChargesPerMonth, "maxChargesPerMonth");
        if (minMonths < MIN_RECURRING_MONTHS) {
            throw new IllegalArgumentException(
                    "minMonths must be at least %d: %d".formatted(MIN_RECURRING_MONTHS, minMonths));
        }
        if (maxVariationPct.signum() < 0) {
            throw new IllegalArgumentException("maxVariationPct must not be negative: " + maxVariationPct);
        }
        if (lookbackMonths < minMonths) {
            throw new IllegalArgumentException("lookbackMonths must be at least minMonths: " + lookbackMonths);
        }
        if (maxMonthsSinceLast < 0) {
            throw new IllegalArgumentException("maxMonthsSinceLast must not be negative: " + maxMonthsSinceLast);
        }
        if (maxChargesPerMonth.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("maxChargesPerMonth must be at least 1: " + maxChargesPerMonth);
        }
    }
}
