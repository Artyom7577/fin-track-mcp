package com.artyomh.finance.domain.money;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MoneyTest {

    @Test
    void amountIsNormalizedToTheCurrencyScale() {
        Money money = new Money(new BigDecimal("1500"), Currency.AMD);

        assertThat(money.amount()).isEqualTo(new BigDecimal("1500.00"));
    }

    @Test
    void sameAmountWithDifferentScaleIsEqual() {
        assertThat(new Money(new BigDecimal("9.9"), Currency.USD))
                .isEqualTo(new Money(new BigDecimal("9.900"), Currency.USD))
                .hasSameHashCodeAs(new Money(new BigDecimal("9.90"), Currency.USD));
    }

    @Test
    void sameAmountInDifferentCurrenciesIsNotEqual() {
        assertThat(new Money(BigDecimal.TEN, Currency.USD)).isNotEqualTo(new Money(BigDecimal.TEN, Currency.AMD));
    }

    @Test
    void tooManyDecimalsAreRejectedNotRounded() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(new BigDecimal("9.999"), Currency.USD))
                .withMessageContaining("too many decimals");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
    void zeroOrNegativeAmountIsRejected(String amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(new BigDecimal(amount), Currency.AMD));
    }

    @Test
    void nullsAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> new Money(null, Currency.AMD));
        assertThatNullPointerException().isThrownBy(() -> new Money(BigDecimal.ONE, null));
    }

    @Test
    void convertsToMinorUnits() {
        assertThat(new Money(new BigDecimal("9.99"), Currency.USD).toMinorUnits()).isEqualTo(999L);
        assertThat(new Money(new BigDecimal("1500"), Currency.AMD).toMinorUnits()).isEqualTo(150_000L);
    }

    @ParameterizedTest
    @EnumSource(Currency.class)
    void minorUnitsRoundTripForEveryCurrency(Currency currency) {
        Money money = Money.ofMinorUnits(123_456L, currency);

        assertThat(money.toMinorUnits()).isEqualTo(123_456L);
    }
}
