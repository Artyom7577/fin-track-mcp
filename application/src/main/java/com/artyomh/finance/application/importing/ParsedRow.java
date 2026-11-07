package com.artyomh.finance.application.importing;

import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;

import java.time.LocalDate;
import java.util.Objects;

public record ParsedRow(int lineNumber, LocalDate date, String description, Direction direction, Money money) {

    public ParsedRow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(money, "money");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank, line " + lineNumber);
        }
    }
}
