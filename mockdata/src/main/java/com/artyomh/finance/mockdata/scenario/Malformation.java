package com.artyomh.finance.mockdata.scenario;

/**
 * A deliberately broken statement row, for parser error tests.
 */
public enum Malformation {
    BAD_DATE,
    TEXT_IN_AMOUNT,
    MISSING_AMOUNT,
    UNSUPPORTED_CURRENCY
}
