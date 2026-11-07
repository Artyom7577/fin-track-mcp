package com.artyomh.finance.adapter.parser.evoca;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AmountTextTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1234.50        | 1234.50",
            "1 234.50       | 1234.50",
            "1 234 567 | 1234567",
            "1 234.5   | 1234.5",
            "12             | 12",
            "-500           | -500",
            "'  9.99 '      | 9.99",
            "1234567.00     | 1234567.00",
    })
    void readsPlainAndSpaceGroupedAmounts(String text, String expected) {
        assertThat(AmountText.parse(text)).hasValueSatisfying(
                amount -> assertThat(amount).isEqualByComparingTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,500", "9,99", "1.234,50", "1,234.50", "1 23", "1 234 567", "125OO", "$12",
            "(12)", "12-", ".5", "5.", "", "- 5", "１２", "1e3"})
    void rejectsEverythingElse(String text) {
        assertThat(AmountText.parse(text)).isEmpty();
    }

    @Test
    void doesNotDependOnTheDefaultLocale() {
        Locale locale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("hy", "AM"));
            assertThat(AmountText.parse("1 234.50")).hasValueSatisfying(
                    amount -> assertThat(amount).isEqualByComparingTo("1234.50"));
        } finally {
            Locale.setDefault(locale);
        }
    }
}
