package com.artyomh.finance.mockdata.scenario;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Where a variable expense draws its amount from; every amount is a whole price step (10 AMD, 1 USD).
 */
public sealed interface AmountSource {

    Currency currency();

    Money smallest();

    /**
     * The smallest price difference in a currency, as real prices are written: whole tens of drams, whole dollars.
     */
    static BigDecimal stepOf(Currency currency) {
        return switch (currency) {
            case AMD -> BigDecimal.TEN;
            case USD -> BigDecimal.ONE;
        };
    }

    /**
     * Rounds to the nearest whole price step, half to even; may give zero.
     */
    static BigDecimal roundToStep(BigDecimal amount, Currency currency) {
        BigDecimal step = stepOf(currency);
        return amount.divide(step, 0, RoundingMode.HALF_EVEN).multiply(step);
    }

    private static void requireWholeSteps(Money amount) {
        if (amount.amount().remainder(stepOf(amount.currency())).signum() != 0) {
            throw new IllegalArgumentException("amount must be a multiple of " + stepOf(amount.currency())
                    + " " + amount.currency() + ": " + amount.amount().toPlainString());
        }
    }

    record Between(Money min, Money max) implements AmountSource {

        public Between {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            if (min.currency() != max.currency()) {
                throw new IllegalArgumentException("min and max must share a currency");
            }
            if (min.amount().compareTo(max.amount()) > 0) {
                throw new IllegalArgumentException("min must not exceed max");
            }
            requireWholeSteps(min);
            requireWholeSteps(max);
        }

        @Override
        public Currency currency() {
            return min.currency();
        }

        @Override
        public Money smallest() {
            return min;
        }
    }

    record OneOf(List<Money> choices) implements AmountSource {

        public OneOf {
            choices = List.copyOf(choices);
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("choices must not be empty");
            }
            Currency currency = choices.getFirst().currency();
            if (choices.stream().anyMatch(choice -> choice.currency() != currency)) {
                throw new IllegalArgumentException("choices must share a currency");
            }
            choices.forEach(AmountSource::requireWholeSteps);
        }

        @Override
        public Currency currency() {
            return choices.getFirst().currency();
        }

        @Override
        public Money smallest() {
            return choices.stream().min(Comparator.comparing(Money::amount)).orElseThrow();
        }
    }
}
