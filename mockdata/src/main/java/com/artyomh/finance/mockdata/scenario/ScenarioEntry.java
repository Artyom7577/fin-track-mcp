package com.artyomh.finance.mockdata.scenario;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * One money flow of a scenario, expanded by the generator into dated rows month by month.
 */
public sealed interface ScenarioEntry {

    Payee payee();

    Direction direction();

    Currency currency();

    /**
     * A fixed amount on a fixed day; a day past the month's end falls on its last day.
     */
    record Recurring(Payee payee, Direction direction, Money amount, int dayOfMonth, YearMonth firstMonth,
                     YearMonth lastMonth) implements ScenarioEntry {

        private static final int MAX_DAY = 31;

        public Recurring {
            Objects.requireNonNull(payee, "payee");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(firstMonth, "firstMonth");
            Objects.requireNonNull(lastMonth, "lastMonth");
            if (dayOfMonth < 1 || dayOfMonth > MAX_DAY) {
                throw new IllegalArgumentException("dayOfMonth must be 1..31: " + dayOfMonth);
            }
            if (firstMonth.isAfter(lastMonth)) {
                throw new IllegalArgumentException("firstMonth must not be after lastMonth");
            }
        }

        @Override
        public Currency currency() {
            return amount.currency();
        }

        public boolean isActiveIn(YearMonth month) {
            return !month.isBefore(firstMonth) && !month.isAfter(lastMonth);
        }
    }

    /**
     * A random amount a random number of times per month, optionally scaled per calendar month (gas in winter).
     */
    record Variable(Payee payee, Direction direction, AmountSource amounts, Frequency frequency,
                    Map<Month, BigDecimal> multipliers) implements ScenarioEntry {

        public Variable {
            Objects.requireNonNull(payee, "payee");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amounts, "amounts");
            Objects.requireNonNull(frequency, "frequency");
            Objects.requireNonNull(multipliers, "multipliers");
            if (multipliers.values().stream().anyMatch(factor -> factor.signum() <= 0)) {
                throw new IllegalArgumentException("multipliers must be positive");
            }
            Map<Month, BigDecimal> copy = new EnumMap<>(Month.class);
            copy.putAll(multipliers);
            multipliers = Collections.unmodifiableMap(copy);
            requireNoZeroAmount(amounts, multipliers);
        }

        public Variable(Payee payee, Direction direction, AmountSource amounts, Frequency frequency) {
            this(payee, direction, amounts, frequency, Map.of());
        }

        @Override
        public Currency currency() {
            return amounts.currency();
        }

        public BigDecimal multiplierFor(Month month) {
            return multipliers.getOrDefault(month, BigDecimal.ONE);
        }

        // the smallest amount times the smallest factor must still round to a whole step, never to zero
        private static void requireNoZeroAmount(AmountSource amounts, Map<Month, BigDecimal> multipliers) {
            BigDecimal factor = multipliers.values().stream().reduce(BigDecimal.ONE, BigDecimal::min);
            BigDecimal lowest = amounts.smallest().amount().multiply(factor);
            if (AmountSource.roundToStep(lowest, amounts.currency()).signum() == 0) {
                throw new IllegalArgumentException("amount can round to zero: " + lowest.toPlainString());
            }
        }
    }

    /**
     * An exact row on an exact date, for stories and edge cases tests assert on.
     */
    record Planted(Payee payee, Direction direction, Money amount, LocalDate date) implements ScenarioEntry {

        public Planted {
            Objects.requireNonNull(payee, "payee");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(date, "date");
        }

        @Override
        public Currency currency() {
            return amount.currency();
        }
    }
}
