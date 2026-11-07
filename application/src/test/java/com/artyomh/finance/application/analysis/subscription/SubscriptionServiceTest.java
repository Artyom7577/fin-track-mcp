package com.artyomh.finance.application.analysis.subscription;

import com.artyomh.finance.application.fake.InMemoryTransactionRepository;
import com.artyomh.finance.domain.transaction.Transaction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.artyomh.finance.application.fake.TestTransactions.amd;
import static com.artyomh.finance.domain.transaction.Category.OTHER_INCOME;
import static com.artyomh.finance.domain.transaction.Category.SUBSCRIPTIONS;
import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionServiceTest {

    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final SubscriptionService service =
            new SubscriptionService(repository, MonthlyRecurrenceDetectorTest.DEFAULTS);

    @Test
    void totalsCountOnlyActiveSubscriptions() {
        saveMonthly("NETFLIX", "5000", 8, 9, 10);
        saveMonthly("SPOTIFY", "2000", 4, 5, 6);

        SubscriptionReport report = service.findSubscriptions();

        assertThat(report.subscriptions()).hasSize(2);
        assertThat(report.activeMonthlyAmd()).isEqualByComparingTo("5000");
        assertThat(report.activeYearlyAmd()).isEqualByComparingTo("60000");
    }

    @Test
    void chargesOlderThanTheLookbackAreIgnored() {
        saveMonthly2025("NETFLIX", "5000", 1, 2, 3);
        saveMonthly("SAS", "1000", 10);

        assertThat(service.findSubscriptions().subscriptions()).isEmpty();
    }

    @Test
    void recurringIncomeIsNotASubscription() {
        List<Transaction> refunds = new ArrayList<>();
        for (int month = 8; month <= 10; month++) {
            refunds.add(amd(LocalDate.of(2026, month, 3), "CASHBACK", OTHER_INCOME, "1000"));
        }
        repository.saveNew(refunds);

        assertThat(service.findSubscriptions().subscriptions()).isEmpty();
    }

    @Test
    void emptyDatabaseGivesAnEmptyReport() {
        SubscriptionReport report = service.findSubscriptions();

        assertThat(report.subscriptions()).isEmpty();
        assertThat(report.activeMonthlyAmd()).isEqualByComparingTo("0");
    }

    private void saveMonthly(String merchant, String amount, int... months) {
        save(2026, merchant, amount, months);
    }

    private void saveMonthly2025(String merchant, String amount, int... months) {
        save(2025, merchant, amount, months);
    }

    private void save(int year, String merchant, String amount, int... months) {
        List<Transaction> rows = new ArrayList<>();
        for (int month : months) {
            rows.add(amd(LocalDate.of(year, month, 3), merchant, SUBSCRIPTIONS, amount));
        }
        repository.saveNew(rows);
    }
}
