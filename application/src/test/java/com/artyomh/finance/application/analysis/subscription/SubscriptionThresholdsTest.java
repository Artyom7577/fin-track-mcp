package com.artyomh.finance.application.analysis.subscription;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SubscriptionThresholdsTest {

    private static final BigDecimal FIFTEEN = new BigDecimal("15");
    private static final BigDecimal ONE_AND_A_HALF = new BigDecimal("1.5");

    @Test
    void defaultsAreValid() {
        assertThatNoException().isThrownBy(() -> new SubscriptionThresholds(3, FIFTEEN, 12, 1, ONE_AND_A_HALF));
    }

    @Test
    void fewerThanTwoMonthsIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SubscriptionThresholds(1, FIFTEEN, 12, 1, ONE_AND_A_HALF))
                .withMessageContaining("at least 2");
    }

    @Test
    void negativeVariationIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SubscriptionThresholds(3, new BigDecimal("-1"), 12, 1, ONE_AND_A_HALF));
    }

    @Test
    void lookbackShorterThanMinMonthsIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SubscriptionThresholds(3, FIFTEEN, 2, 1, ONE_AND_A_HALF));
    }

    @Test
    void negativeMonthsSinceLastIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SubscriptionThresholds(3, FIFTEEN, 12, -1, ONE_AND_A_HALF));
    }

    @Test
    void lessThanOneChargePerMonthIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SubscriptionThresholds(3, FIFTEEN, 12, 1, new BigDecimal("0.9")));
    }
}
