package com.artyomh.finance.application.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PercentTest {

    @ParameterizedTest
    @CsvSource({
            "1,      3,   33.3",
            "2,      3,   66.7",
            "50,     100, 50.0",
            "1,      8,   12.5",
            "0.25,   100, 0.2",
            "0.35,   100, 0.4",
            "-25,    100, -25.0",
            "-0.25,  100, -0.2",
            "-0.35,  100, -0.4",
            "150,    100, 150.0",
    })
    void roundsHalfEvenToOneDecimal(String part, String whole, String expected) {
        assertThat(Percent.of(new BigDecimal(part), new BigDecimal(whole))).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void percentageOfZeroIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Percent.of(BigDecimal.ONE, BigDecimal.ZERO));
    }
}
