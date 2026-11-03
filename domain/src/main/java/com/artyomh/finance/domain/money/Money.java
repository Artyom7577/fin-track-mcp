package com.artyomh.finance.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount.toPlainString());
        }
        if (amount.stripTrailingZeros().scale() > currency.minorDigits()) {
            throw new IllegalArgumentException("too many decimals for " + currency + ": " + amount.toPlainString());
        }
        // one scale per currency, so equals and hashing see 9.9 and 9.90 as the same amount
        amount = amount.setScale(currency.minorDigits(), RoundingMode.UNNECESSARY);
    }

    public static Money ofMinorUnits(long minorUnits, Currency currency) {
        return new Money(BigDecimal.valueOf(minorUnits, currency.minorDigits()), currency);
    }

    public long toMinorUnits() {
        return amount.movePointRight(currency.minorDigits()).longValueExact();
    }
}
