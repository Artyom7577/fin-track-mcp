package com.artyomh.finance.domain.transaction;

import com.artyomh.finance.domain.money.Money;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The facts that identify a statement row; equal keys in one file are told apart by an occurrence index.
 */
public record TransactionKey(LocalDate date, String description, Direction direction, Money money) {

    private static final Pattern SPACES = Pattern.compile("\\s+");

    public TransactionKey {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(money, "money");
        description = SPACES.matcher(description.strip()).replaceAll(" ").toUpperCase(Locale.ROOT);
        if (description.isEmpty()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }
}
