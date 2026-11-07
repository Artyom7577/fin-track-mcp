package com.artyomh.finance.mockdata.generator;

import java.util.Objects;

/**
 * A broken row as raw cell text; it has no balance and does not move the running balance.
 */
public record MalformedRow(String date, String description, String debit, String credit, String currency)
        implements StatementLine {

    public MalformedRow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(debit, "debit");
        Objects.requireNonNull(credit, "credit");
        Objects.requireNonNull(currency, "currency");
    }
}
