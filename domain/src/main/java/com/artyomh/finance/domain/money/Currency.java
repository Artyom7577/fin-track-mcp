package com.artyomh.finance.domain.money;

public enum Currency {
    AMD(2),
    USD(2);

    private final int minorDigits;

    Currency(int minorDigits) {
        this.minorDigits = minorDigits;
    }

    public int minorDigits() {
        return minorDigits;
    }
}
