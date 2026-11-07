package com.artyomh.finance.mockdata.writer;

import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.mockdata.generator.GeneratedRow;
import com.artyomh.finance.mockdata.generator.GeneratedStatement;
import com.artyomh.finance.mockdata.generator.MalformedRow;
import com.artyomh.finance.mockdata.generator.StatementLine;

import java.util.List;

/**
 * The mock Evoca-style column layout; the XLSX writer repeats its order with typed cells, pinned by a round-trip test.
 */
final class StatementLayout {

    static final List<String> HEADERS = List.of(
            "Ամսաթիվ / Date",
            "Նկարագրություն / Description",
            "Ելք / Debit",
            "Մուտք / Credit",
            "Արժույթ / Currency",
            "Մնացորդ / Balance");

    private StatementLayout() {
    }

    /**
     * The six cells of a line as text, with an empty string for an empty cell.
     */
    static List<String> cells(StatementLine line) {
        return switch (line) {
            case GeneratedRow row -> List.of(
                    GeneratedStatement.DATE_FORMAT.format(row.date()),
                    row.description(),
                    amountIf(row, Direction.EXPENSE),
                    amountIf(row, Direction.INCOME),
                    row.money().currency().name(),
                    row.balanceAfter().toPlainString());
            case MalformedRow row -> List.of(row.date(), row.description(), row.debit(), row.credit(),
                    row.currency(), "");
        };
    }

    private static String amountIf(GeneratedRow row, Direction direction) {
        if (row.direction() == direction) {
            return row.money().amount().toPlainString();
        }
        return "";
    }
}
