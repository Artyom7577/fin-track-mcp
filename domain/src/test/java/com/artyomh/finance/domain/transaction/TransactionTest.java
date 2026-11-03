package com.artyomh.finance.domain.transaction;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TransactionTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);
    private static final TransactionId ID = new TransactionId("0123456789abcdef");
    private static final Money NETFLIX = new Money(new BigDecimal("9.99"), Currency.USD);
    private static final BigDecimal USD_RATE = new BigDecimal("385.00");

    @Test
    void usdAmountIsConvertedWithHalfEvenAtAmdScale() {
        Transaction tx = usd(new BigDecimal("3846.1500"));

        assertThat(tx.amountAmd()).isEqualTo(new BigDecimal("3846.15"));
    }

    @Test
    void amountAmdThatDoesNotMatchMoneyTimesRateIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> usd(new BigDecimal("3846")));
    }

    @Test
    void amdTransactionMustHaveRateOne() {
        Money money = new Money(new BigDecimal("5000"), Currency.AMD);

        assertThatIllegalArgumentException().isThrownBy(() -> new Transaction(ID, DATE, "SAS", "SAS",
                Direction.EXPENSE, money, new BigDecimal("10000"), new BigDecimal("2"), Category.GROCERIES, "f.xlsx"));
    }

    @Test
    void amdTransactionAmountAmdEqualsItsMoney() {
        Money money = new Money(new BigDecimal("5000"), Currency.AMD);

        Transaction tx = new Transaction(ID, DATE, "SAS", "SAS", Direction.EXPENSE, money,
                new BigDecimal("5000"), BigDecimal.ONE, Category.GROCERIES, "f.xlsx");

        assertThat(tx.amountAmd()).isEqualByComparingTo(money.amount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-385"})
    void nonPositiveRateIsRejected(String rate) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Transaction(ID, DATE, "NETFLIX", "NETFLIX",
                Direction.EXPENSE, NETFLIX, BigDecimal.ONE, new BigDecimal(rate), Category.SUBSCRIPTIONS, "f.xlsx"));
    }

    @Test
    void categoryOfTheOtherDirectionIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Transaction(ID, DATE, "NETFLIX", "NETFLIX", Direction.EXPENSE, NETFLIX,
                        new BigDecimal("3846.15"), USD_RATE, Category.SALARY, "f.xlsx"))
                .withMessageContaining("SALARY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankTextFieldsAreRejected(String blank) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Transaction(ID, DATE, blank, "NETFLIX",
                Direction.EXPENSE, NETFLIX, new BigDecimal("3846.15"), USD_RATE, Category.SUBSCRIPTIONS, "f.xlsx"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Transaction(ID, DATE, "NETFLIX", blank,
                Direction.EXPENSE, NETFLIX, new BigDecimal("3846.15"), USD_RATE, Category.SUBSCRIPTIONS, "f.xlsx"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Transaction(ID, DATE, "NETFLIX", "NETFLIX",
                Direction.EXPENSE, NETFLIX, new BigDecimal("3846.15"), USD_RATE, Category.SUBSCRIPTIONS, blank));
    }

    @Test
    void withCategoryChangesOnlyTheCategory() {
        Transaction original = usd(new BigDecimal("3846.15"));

        Transaction changed = original.withCategory(Category.OTHER);

        assertThat(changed.category()).isEqualTo(Category.OTHER);
        assertThat(changed).usingRecursiveComparison().ignoringFields("category").isEqualTo(original);
    }

    @Test
    void monthComesFromTheDate() {
        assertThat(usd(new BigDecimal("3846.15")).month()).isEqualTo(YearMonth.of(2026, 10));
    }

    private static Transaction usd(BigDecimal amountAmd) {
        return new Transaction(ID, DATE, "NETFLIX.COM", "NETFLIX", Direction.EXPENSE, NETFLIX,
                amountAmd, USD_RATE, Category.SUBSCRIPTIONS, "statement.xlsx");
    }
}
