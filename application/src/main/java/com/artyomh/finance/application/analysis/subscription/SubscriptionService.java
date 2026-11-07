package com.artyomh.finance.application.analysis.subscription;

import com.artyomh.finance.application.port.TransactionRepository;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.domain.transaction.Transaction;

import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class SubscriptionService {

    private final TransactionRepository transactions;
    private final MonthlyRecurrenceDetector detector;
    private final int lookbackMonths;

    public SubscriptionService(TransactionRepository transactions, SubscriptionThresholds thresholds) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.detector = new MonthlyRecurrenceDetector(thresholds);
        this.lookbackMonths = thresholds.lookbackMonths();
    }

    /**
     * Looks back from the latest imported month, not today, so results depend only on the data.
     */
    public SubscriptionReport findSubscriptions() {
        List<YearMonth> months = transactions.findMonths();
        if (months.isEmpty()) {
            return new SubscriptionReport(List.of());
        }
        YearMonth asOf = months.getLast();
        Collection<Transaction> expenses = transactions
                .findBetween(asOf.minusMonths(lookbackMonths - 1L).atDay(1), asOf.atEndOfMonth()).stream()
                .filter(t -> t.direction() == Direction.EXPENSE)
                .toList();
        return new SubscriptionReport(detector.detect(expenses, asOf));
    }
}
