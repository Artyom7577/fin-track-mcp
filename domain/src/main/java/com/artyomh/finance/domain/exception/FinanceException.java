package com.artyomh.finance.domain.exception;

/**
 * Base of every expected failure; not sealed, since sealed subtypes must share its package and each module owns its own.
 */
public abstract class FinanceException extends RuntimeException {

    protected FinanceException(String message) {
        super(message);
    }

    public abstract String code();
}
