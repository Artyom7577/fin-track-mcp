package com.artyomh.finance.domain.transaction;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TransactionIdTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);
    private static final Money COFFEE = new Money(new BigDecimal("1200"), Currency.AMD);

    @Test
    void sameFactsGiveTheSameId() {
        assertThat(TransactionId.of(key("CAFE"), 0)).isEqualTo(TransactionId.of(key("CAFE"), 0));
    }

    @Test
    void recipeIsStableSoReimportsDeduplicate() {
        // a changed recipe re-ids all stored history; update only with a migration plan
        assertThat(TransactionId.of(key("CAFE"), 0).value()).isEqualTo("61a49a3197af4f40");
    }

    @Test
    void idIsSixteenLowercaseHexCharacters() {
        assertThat(TransactionId.of(key("CAFE"), 0).value()).matches("[0-9a-f]{16}");
    }

    @Test
    void occurrenceIndexSeparatesIdenticalRowsOfOneDay() {
        assertThat(TransactionId.of(key("CAFE"), 0)).isNotEqualTo(TransactionId.of(key("CAFE"), 1));
    }

    @Test
    void descriptionCaseAndWhitespaceDoNotChangeTheId() {
        assertThat(TransactionId.of(key("  cafe   jazzve "), 0)).isEqualTo(TransactionId.of(key("CAFE JAZZVE"), 0));
    }

    @Test
    void amountScaleDoesNotChangeTheId() {
        TransactionKey plain = new TransactionKey(DATE, "CAFE", Direction.EXPENSE,
                new Money(new BigDecimal("9.9"), Currency.USD));
        TransactionKey padded = new TransactionKey(DATE, "CAFE", Direction.EXPENSE,
                new Money(new BigDecimal("9.90"), Currency.USD));

        assertThat(TransactionId.of(plain, 0)).isEqualTo(TransactionId.of(padded, 0));
    }

    @Test
    void refundOfTheSameAmountGetsItsOwnId() {
        TransactionKey refund = new TransactionKey(DATE, "CAFE", Direction.INCOME, COFFEE);

        assertThat(TransactionId.of(refund, 0)).isNotEqualTo(TransactionId.of(key("CAFE"), 0));
    }

    @Test
    void negativeOccurrenceIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> TransactionId.of(key("CAFE"), -1));
    }

    @Test
    void blankDescriptionIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> key("   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0123456789ABCDEF", "0123456789abcdeg", "0123456789abcdef0"})
    void malformedIdValueIsRejected(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new TransactionId(value));
    }

    private static TransactionKey key(String description) {
        return new TransactionKey(DATE, description, Direction.EXPENSE, COFFEE);
    }
}
