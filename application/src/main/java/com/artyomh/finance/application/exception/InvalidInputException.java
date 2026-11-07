package com.artyomh.finance.application.exception;

import com.artyomh.finance.domain.exception.FinanceException;

public final class InvalidInputException extends FinanceException {

    private static final String CODE = "INVALID_INPUT";

    public InvalidInputException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return CODE;
    }
}
