package com.artyomh.finance.mockdata.generator;

import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A valid row; {@code balanceAfter} is the running balance of {@code money}'s currency and may be zero.
 */
public record GeneratedRow(LocalDate date, String description, Direction direction, Money money,
                           BigDecimal balanceAfter) implements StatementLine {

    public GeneratedRow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(money, "money");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
    }
}
