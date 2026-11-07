package com.artyomh.finance.mockdata.generator;

/**
 * One line of a statement body: a valid row, or a deliberately broken one.
 */
public sealed interface StatementLine permits GeneratedRow, MalformedRow {
}
