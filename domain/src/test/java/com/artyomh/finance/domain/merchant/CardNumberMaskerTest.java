package com.artyomh.finance.domain.merchant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CardNumberMaskerTest {

    // transaction ids hash the masked text, so this output is pinned: changing it re-ids stored history
    @ParameterizedTest
    @CsvSource({
            "POS 4578123456789012 SAS,     POS ****9012 SAS",
            "SAS 4578 1234 5678 9012,      SAS ****9012",
            "SAS 4578-1234-5678-9012,      SAS ****9012",
            "CARD4578123456789012,         CARD****9012",
            "457812345678,                 ****5678",
            "4578123456789012345,          ****2345",
    })
    void masksEveryFullCardNumberKeepingTheLastFour(String text, String expected) {
        assertThat(CardNumberMasker.mask(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POS ****9012 SAS", "4578****9012", "YANDEX GO 2026 10 05", "45781234567", "ԵՐԵՎԱՆ ՍԻԹԻ"})
    void leavesShortDigitRunsAndMaskedNumbersAlone(String text) {
        assertThat(CardNumberMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void maskingIsIdempotent() {
        String once = CardNumberMasker.mask("POS 4578 1234 5678 9012 SAS");

        assertThat(CardNumberMasker.mask(once)).isEqualTo(once);
    }
}
