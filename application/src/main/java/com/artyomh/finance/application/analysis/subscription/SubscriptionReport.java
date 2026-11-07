package com.artyomh.finance.application.analysis.subscription;

import com.artyomh.finance.domain.money.Currency;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

public record SubscriptionReport(List<Subscription> subscriptions) {

    private static final BigDecimal ZERO_AMD = BigDecimal.ZERO.setScale(Currency.AMD.minorDigits());

    public SubscriptionReport {
        subscriptions = List.copyOf(subscriptions);
    }

    public BigDecimal activeMonthlyAmd() {
        return sumOfActive(Subscription::monthlyAmd);
    }

    public BigDecimal activeYearlyAmd() {
        return sumOfActive(Subscription::yearlyAmd);
    }

    private BigDecimal sumOfActive(Function<Subscription, BigDecimal> amount) {
        return subscriptions.stream().filter(Subscription::isActive).map(amount).reduce(ZERO_AMD, BigDecimal::add);
    }
}
