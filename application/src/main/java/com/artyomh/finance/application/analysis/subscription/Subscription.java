package com.artyomh.finance.application.analysis.subscription;

import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Category;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.Set;

public record Subscription(String merchant,
                           Category mainCategory,
                           Money typicalAmount,
                           BigDecimal monthlyAmd,
                           YearMonth firstSeen,
                           LocalDate lastCharged,
                           int months,
                           SubscriptionStatus status) {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final Set<Category> BILL_CATEGORIES =
            EnumSet.of(Category.RENT, Category.UTILITIES, Category.INTERNET_MOBILE);

    public BigDecimal yearlyAmd() {
        return monthlyAmd.multiply(MONTHS_PER_YEAR);
    }

    public SubscriptionKind kind() {
        if (BILL_CATEGORIES.contains(mainCategory)) {
            return SubscriptionKind.BILL;
        }
        return SubscriptionKind.SUBSCRIPTION;
    }

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE;
    }
}
