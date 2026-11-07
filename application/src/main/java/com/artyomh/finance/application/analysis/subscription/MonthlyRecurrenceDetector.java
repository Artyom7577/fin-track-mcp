package com.artyomh.finance.application.analysis.subscription;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Finds charges that repeat across months at a stable amount.
 */
public final class MonthlyRecurrenceDetector {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Comparator<Subscription> ACTIVE_THEN_LARGEST = Comparator
            .comparing(Subscription::status)
            .thenComparing(Subscription::monthlyAmd, Comparator.reverseOrder())
            .thenComparing(Subscription::merchant);

    private record ChargeGroup(String merchant, Currency currency) {
    }

    private final SubscriptionThresholds thresholds;

    public MonthlyRecurrenceDetector(SubscriptionThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    public List<Subscription> detect(Collection<Transaction> expenses, YearMonth asOf) {
        Objects.requireNonNull(asOf, "asOf");
        Map<ChargeGroup, List<Transaction>> groups = expenses.stream()
                .collect(Collectors.groupingBy(t -> new ChargeGroup(t.merchant(), t.money().currency())));
        return groups.values().stream()
                .map(charges -> toSubscription(charges, asOf))
                .flatMap(Optional::stream)
                .sorted(ACTIVE_THEN_LARGEST)
                .toList();
    }

    private Optional<Subscription> toSubscription(List<Transaction> charges, YearMonth asOf) {
        NavigableSet<YearMonth> months = charges.stream()
                .map(Transaction::month)
                .collect(Collectors.toCollection(TreeSet::new));
        if (months.size() < thresholds.minMonths() || tooFrequent(charges.size(), months.size())) {
            return Optional.empty();
        }
        Money typical = median(charges);
        if (!amountsAreStable(charges, typical)) {
            return Optional.empty();
        }
        LocalDate lastCharged = charges.stream().map(Transaction::date).max(Comparator.naturalOrder()).orElseThrow();
        Transaction first = charges.getFirst();
        return Optional.of(new Subscription(first.merchant(), mainCategory(charges), typical,
                monthlyAmd(charges, months.size()), months.first(), lastCharged, months.size(),
                statusAt(lastCharged, asOf)));
    }

    private boolean tooFrequent(int charges, int months) {
        BigDecimal allowed = thresholds.maxChargesPerMonth().multiply(BigDecimal.valueOf(months));
        return BigDecimal.valueOf(charges).compareTo(allowed) > 0;
    }

    // compared unrounded: a displayed 15.0% may really be 15.04% and must fail a 15% limit
    private boolean amountsAreStable(Collection<Transaction> charges, Money typical) {
        BigDecimal allowedDeviation = typical.amount().multiply(thresholds.maxVariationPct());
        return charges.stream().allMatch(charge -> charge.money().amount().subtract(typical.amount()).abs()
                .multiply(HUNDRED).compareTo(allowedDeviation) <= 0);
    }

    private SubscriptionStatus statusAt(LocalDate lastCharged, YearMonth asOf) {
        if (ChronoUnit.MONTHS.between(YearMonth.from(lastCharged), asOf) > thresholds.maxMonthsSinceLast()) {
            return SubscriptionStatus.POSSIBLY_CANCELLED;
        }
        return SubscriptionStatus.ACTIVE;
    }

    private static Money median(Collection<Transaction> charges) {
        List<BigDecimal> amounts = charges.stream().map(t -> t.money().amount()).sorted().toList();
        Currency currency = charges.iterator().next().money().currency();
        int middle = amounts.size() / 2;
        if (amounts.size() % 2 == 1) {
            return new Money(amounts.get(middle), currency);
        }
        BigDecimal mean = amounts.get(middle - 1).add(amounts.get(middle))
                .divide(TWO, currency.minorDigits(), RoundingMode.HALF_EVEN);
        return new Money(mean, currency);
    }

    private static BigDecimal monthlyAmd(Collection<Transaction> charges, int months) {
        BigDecimal total = charges.stream().map(Transaction::amountAmd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(months), Currency.AMD.minorDigits(), RoundingMode.HALF_EVEN);
    }

    private static Category mainCategory(Collection<Transaction> charges) {
        Map<Category, BigDecimal> byCategory = charges.stream().collect(Collectors.groupingBy(
                Transaction::category, Collectors.reducing(BigDecimal.ZERO, Transaction::amountAmd, BigDecimal::add)));
        return byCategory.entrySet().stream()
                .max(Map.Entry.<Category, BigDecimal>comparingByValue()
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElseThrow();
    }
}
