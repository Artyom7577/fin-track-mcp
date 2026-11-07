package com.artyomh.finance.application.analysis.spending;

import com.artyomh.finance.application.exception.InvalidInputException;
import com.artyomh.finance.application.exception.MonthNotFoundException;
import com.artyomh.finance.application.fake.InMemoryTransactionRepository;
import com.artyomh.finance.domain.transaction.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static com.artyomh.finance.application.fake.TestTransactions.amd;
import static com.artyomh.finance.application.fake.TestTransactions.usd;
import static com.artyomh.finance.domain.transaction.Category.CAFES_RESTAURANTS;
import static com.artyomh.finance.domain.transaction.Category.GROCERIES;
import static com.artyomh.finance.domain.transaction.Category.RENT;
import static com.artyomh.finance.domain.transaction.Category.SALARY;
import static com.artyomh.finance.domain.transaction.Category.SHOPPING;
import static com.artyomh.finance.domain.transaction.Category.SUBSCRIPTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class SpendingServiceTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    private static final int MAX_LIMIT = 50;

    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final SpendingService service = new SpendingService(repository, MAX_LIMIT);

    @Test
    void spendingIsGroupedByExpenseCategoryLargestFirst() {
        save(amd(OCT_5, "LANDLORD", RENT, "300000"),
                amd(OCT_5, "SAS", GROCERIES, "40000"),
                amd(OCT_5, "YEREVAN CITY", GROCERIES, "20000"),
                amd(OCT_5, "JAZZVE", CAFES_RESTAURANTS, "40000"),
                amd(OCT_5, "EMPLOYER", SALARY, "800000"));

        MonthlySpending result = service.spendingByCategory(OCTOBER);

        assertThat(result.spentAmd()).isEqualByComparingTo("400000");
        assertThat(result.incomeAmd()).isEqualByComparingTo("800000");
        assertThat(result.categories()).containsExactly(
                new CategorySpending(RENT, new BigDecimal("300000.00"), new BigDecimal("75.0"), 1),
                new CategorySpending(GROCERIES, new BigDecimal("60000.00"), new BigDecimal("15.0"), 2),
                new CategorySpending(CAFES_RESTAURANTS, new BigDecimal("40000.00"), new BigDecimal("10.0"), 1));
    }

    @Test
    void netAndSavingsRateAreDerivedFromTheTotals() {
        save(amd(OCT_5, "LANDLORD", RENT, "544000"), amd(OCT_5, "EMPLOYER", SALARY, "800000"));

        MonthlySpending result = service.spendingByCategory(OCTOBER);

        assertThat(result.netAmd()).isEqualByComparingTo("256000");
        assertThat(result.savingsRatePct()).contains(new BigDecimal("32.0"));
    }

    @Test
    void spendingMoreThanEarnedGivesANegativeSavingsRate() {
        save(amd(OCT_5, "LANDLORD", RENT, "600000"), amd(OCT_5, "EMPLOYER", SALARY, "500000"));

        MonthlySpending result = service.spendingByCategory(OCTOBER);

        assertThat(result.netAmd()).isEqualByComparingTo("-100000");
        assertThat(result.savingsRatePct()).contains(new BigDecimal("-20.0"));
    }

    @Test
    void incomeOnlyMonthHasNoSpendingAndNoMerchants() {
        save(amd(OCT_5, "EMPLOYER", SALARY, "500000"));

        assertThat(service.spendingByCategory(OCTOBER).categories()).isEmpty();
        assertThat(service.spendingByCategory(OCTOBER).spentAmd()).isEqualByComparingTo("0");
        assertThat(service.topMerchants(OCTOBER, 5)).isEmpty();
    }

    @Test
    void monthWithoutIncomeHasNoSavingsRate() {
        save(amd(OCT_5, "SAS", GROCERIES, "1000"));

        assertThat(service.spendingByCategory(OCTOBER).savingsRatePct()).isEmpty();
    }

    @Test
    void usdSpendingIsCountedInAmd() {
        save(usd(OCT_5, "NETFLIX", SUBSCRIPTIONS, "9.99"), amd(OCT_5, "SAS", GROCERIES, "1000"));

        assertThat(service.spendingByCategory(OCTOBER).spentAmd()).isEqualByComparingTo("4846.15");
    }

    @Test
    void sharesAreNotForcedToSumToOneHundred() {
        save(amd(OCT_5, "A", RENT, "1"), amd(OCT_5, "B", GROCERIES, "1"), amd(OCT_5, "C", SHOPPING, "1"));

        assertThat(service.spendingByCategory(OCTOBER).categories())
                .extracting(CategorySpending::sharePct)
                .containsOnly(new BigDecimal("33.3"));
    }

    @Test
    void missingMonthListsTheAvailableMonths() {
        save(amd(LocalDate.of(2026, 5, 1), "SAS", GROCERIES, "1000"), amd(OCT_5, "SAS", GROCERIES, "1000"));

        assertThatThrownBy(() -> service.spendingByCategory(YearMonth.of(2026, 3)))
                .isInstanceOf(MonthNotFoundException.class)
                .hasMessage("No data for 2026-03. Available: 2026-05 to 2026-10");
    }

    @Test
    void topMerchantsAreRankedWithAlphabeticalTiesAndLimited() {
        save(amd(OCT_5, "ZARA", SHOPPING, "5000"),
                amd(OCT_5, "ADIDAS", SHOPPING, "5000"),
                amd(OCT_5, "SAS", GROCERIES, "6000"),
                amd(OCT_5, "SAS", GROCERIES, "4000"),
                amd(OCT_5, "EMPLOYER", SALARY, "900000"));

        List<MerchantSpending> top = service.topMerchants(OCTOBER, 2);

        assertThat(top).containsExactly(
                new MerchantSpending("SAS", GROCERIES, new BigDecimal("10000.00"), new BigDecimal("50.0"), 2),
                new MerchantSpending("ADIDAS", SHOPPING, new BigDecimal("5000.00"), new BigDecimal("25.0"), 1));
    }

    @Test
    void mainCategoryIsTheLargestWithTiesInEnumOrder() {
        save(amd(OCT_5, "SAS", GROCERIES, "1000"),
                amd(OCT_5, "SAS", SHOPPING, "1000"),
                amd(OCT_5, "YEREVAN MALL", GROCERIES, "100"),
                amd(OCT_5, "YEREVAN MALL", SHOPPING, "900"));

        assertThat(service.topMerchants(OCTOBER, 2))
                .extracting(MerchantSpending::merchant, MerchantSpending::mainCategory)
                .containsExactly(tuple("SAS", GROCERIES),
                        tuple("YEREVAN MALL", SHOPPING));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51})
    void limitOutsideTheAllowedRangeIsRejected(int limit) {
        save(amd(OCT_5, "SAS", GROCERIES, "1000"));

        assertThatThrownBy(() -> service.topMerchants(OCTOBER, limit))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("1-50");
    }

    @Test
    void listMonthsSummarizesEveryMonthOldestFirst() {
        save(amd(LocalDate.of(2026, 9, 30), "SAS", GROCERIES, "1000"),
                amd(OCT_5, "EMPLOYER", SALARY, "5000"),
                amd(OCT_5, "SAS", GROCERIES, "2000"));

        List<MonthSummary> months = service.listMonths();

        assertThat(months).containsExactly(
                new MonthSummary(YearMonth.of(2026, 9), new BigDecimal("0.00"), new BigDecimal("1000.00"), 1),
                new MonthSummary(OCTOBER, new BigDecimal("5000.00"), new BigDecimal("2000.00"), 2));
        assertThat(months.getLast().netAmd()).isEqualByComparingTo("3000");
    }

    @Test
    void emptyDatabaseHasNoMonths() {
        assertThat(service.listMonths()).isEmpty();
        assertThat(service.availableMonths()).isEmpty();
        assertThatThrownBy(() -> service.spendingByCategory(OCTOBER)).hasMessage("No statements imported yet; import a statement first.");
    }

    private void save(Transaction... rows) {
        repository.saveNew(List.of(rows));
    }
}
