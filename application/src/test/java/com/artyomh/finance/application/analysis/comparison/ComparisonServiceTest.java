package com.artyomh.finance.application.analysis.comparison;

import com.artyomh.finance.application.analysis.spending.SpendingService;
import com.artyomh.finance.application.exception.InvalidInputException;
import com.artyomh.finance.application.exception.MonthNotFoundException;
import com.artyomh.finance.application.fake.InMemoryTransactionRepository;
import com.artyomh.finance.domain.transaction.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static com.artyomh.finance.application.fake.TestTransactions.amd;
import static com.artyomh.finance.domain.transaction.Category.CAFES_RESTAURANTS;
import static com.artyomh.finance.domain.transaction.Category.GROCERIES;
import static com.artyomh.finance.domain.transaction.Category.RENT;
import static com.artyomh.finance.domain.transaction.Category.SHOPPING;
import static com.artyomh.finance.domain.transaction.Category.TRAVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComparisonServiceTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final LocalDate SEP_5 = LocalDate.of(2026, 9, 5);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);

    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final ComparisonService service = new ComparisonService(new SpendingService(repository, 50));

    @Test
    void changesAreSortedByAbsoluteDeltaWithTiesInEnumOrder() {
        save(amd(SEP_5, "LANDLORD", RENT, "300000"), amd(OCT_5, "LANDLORD", RENT, "300000"),
                amd(SEP_5, "SAS", GROCERIES, "50000"), amd(OCT_5, "SAS", GROCERIES, "40000"),
                amd(OCT_5, "JAZZVE", CAFES_RESTAURANTS, "10000"),
                amd(SEP_5, "ZARA", SHOPPING, "30000"));

        MonthComparison result = service.compareMonths(SEPTEMBER, OCTOBER);

        assertThat(result.changes()).extracting(CategoryChange::category)
                .containsExactly(SHOPPING, GROCERIES, CAFES_RESTAURANTS, RENT);
        assertThat(result.totalDeltaAmd()).isEqualByComparingTo("-30000");
    }

    @Test
    void deltaAndPercentageAreDerivedFromBothAmounts() {
        save(amd(SEP_5, "SAS", GROCERIES, "50000"), amd(OCT_5, "SAS", GROCERIES, "40000"));

        CategoryChange groceries = service.compareMonths(SEPTEMBER, OCTOBER).changes().getFirst();

        assertThat(groceries.amountAAmd()).isEqualByComparingTo("50000");
        assertThat(groceries.amountBAmd()).isEqualByComparingTo("40000");
        assertThat(groceries.deltaAmd()).isEqualByComparingTo("-10000");
        assertThat(groceries.changePct()).contains(new BigDecimal("-20.0"));
    }

    @Test
    void categoryNewInMonthBHasNoPercentage() {
        save(amd(SEP_5, "SAS", GROCERIES, "1000"), amd(OCT_5, "FLYONE", TRAVEL, "90000"));

        CategoryChange travel = service.compareMonths(SEPTEMBER, OCTOBER).changes().getFirst();

        assertThat(travel.category()).isEqualTo(TRAVEL);
        assertThat(travel.amountAAmd()).isEqualByComparingTo("0");
        assertThat(travel.changePct()).isEmpty();
    }

    @Test
    void categoryGoneInMonthBDropsByAHundredPercent() {
        save(amd(SEP_5, "ZARA", SHOPPING, "30000"), amd(OCT_5, "SAS", GROCERIES, "1000"));

        assertThat(service.compareMonths(SEPTEMBER, OCTOBER).changes().getFirst().changePct())
                .contains(new BigDecimal("-100.0"));
    }

    @Test
    void sameMonthTwiceIsRejected() {
        assertThatThrownBy(() -> service.compareMonths(OCTOBER, OCTOBER))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void missingMonthIsReportedWithTheAvailableRange() {
        save(amd(OCT_5, "SAS", GROCERIES, "1000"));

        assertThatThrownBy(() -> service.compareMonths(SEPTEMBER, OCTOBER))
                .isInstanceOf(MonthNotFoundException.class)
                .hasMessage("No data for 2026-09. Available: 2026-10");
    }

    private void save(Transaction... rows) {
        repository.saveNew(List.of(rows));
    }
}
