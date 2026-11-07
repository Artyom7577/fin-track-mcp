package com.artyomh.finance.adapter.parser.exception;

import com.artyomh.finance.domain.exception.FinanceException;

import java.util.Objects;

/**
 * A statement file that cannot be read at all; a bad row is a RowProblem instead.
 */
public final class StatementReadException extends FinanceException {

    private final ReadFailure failure;

    public StatementReadException(ReadFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public ReadFailure failure() {
        return failure;
    }

    @Override
    public String code() {
        return failure.name();
    }
}
