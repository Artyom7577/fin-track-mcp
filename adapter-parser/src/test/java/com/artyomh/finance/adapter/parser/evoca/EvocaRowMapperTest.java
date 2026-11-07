package com.artyomh.finance.adapter.parser.evoca;

import com.artyomh.finance.adapter.parser.format.Cell;
import com.artyomh.finance.adapter.parser.format.SourceLine;
import com.artyomh.finance.application.importing.FileWarning;
import com.artyomh.finance.application.importing.ParseResult;
import com.artyomh.finance.application.importing.ParsedRow;
import com.artyomh.finance.application.importing.RowProblem;
import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EvocaRowMapperTest {

    private static final SourceLine HEADER = line(1, "Date", "Description", "Debit", "Credit", "Currency", "Balance");
    private static final LocalDate MAY_FIRST = LocalDate.of(2026, Month.MAY, 1);

    private final EvocaRowMapper mapper = new EvocaRowMapper(HEADER);

    @Test
    void mapsDebitToExpenseAndCreditToIncome() {
        ParseResult result = map(
                line(2, new Cell.DateCell(MAY_FIRST), text("SHOP"), new Cell.Numeric(1500, "1,500.00"), Cell.BLANK,
                        text("AMD"), new Cell.Numeric(1, "1")),
                line(3, "01.05.2026", "SALARY", "", "850 000.00", "amd", ""));

        assertThat(result.problems()).isEmpty();
        assertThat(result.rows()).containsExactly(
                new ParsedRow(2, MAY_FIRST, "SHOP", Direction.EXPENSE, amd("1500")),
                new ParsedRow(3, MAY_FIRST, "SALARY", Direction.INCOME, amd("850000")));
    }

    @Test
    void roundsDoubleArtefactsButRejectsRealExtraDecimals() {
        ParseResult result = map(
                row(2, new Cell.Numeric(0.1 + 0.2, ""), Cell.BLANK),
                row(3, new Cell.Numeric(1234.567, ""), Cell.BLANK));

        assertThat(result.rows()).singleElement().extracting(ParsedRow::money).isEqualTo(amd("0.30"));
        assertThat(result.problems()).containsExactly(new RowProblem(3, "more than 2 decimal places in Debit column"));
    }

    @Test
    void aZeroBesideAPositiveAmountCountsAsEmpty() {
        ParseResult result = map(row(2, text("5000"), text("0.00")), row(3, new Cell.Numeric(0, "0"), text("12")));

        assertThat(result.rows()).extracting(ParsedRow::direction).containsExactly(Direction.EXPENSE, Direction.INCOME);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''      | ''      | no amount in Debit or Credit",
            "100     | 200     | both Debit and Credit are filled",
            "0       | ''      | amount is zero",
            "0.00    | 0       | amount is zero",
            "-500    | ''      | negative amount in Debit column",
            "''      | -1      | negative amount in Credit column",
            "125OO   | ''      | unreadable amount in Debit column",
            "1,500   | ''      | unreadable amount in Debit column",
            "9.999   | ''      | more than 2 decimal places in Debit column",
    })
    void amountRules(String debit, String credit, String reason) {
        assertThat(map(line(7, "01.05.2026", "SHOP", debit, credit, "AMD", "")).problems())
                .containsExactly(new RowProblem(7, reason));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "31.02.2026        | unreadable date; expected dd.mm.yyyy or yyyy-mm-dd",
            "01/05/2026        | unreadable date; expected dd.mm.yyyy or yyyy-mm-dd",
            "2026/13/01        | unreadable date; expected dd.mm.yyyy or yyyy-mm-dd",
            "''                | missing date",
    })
    void dateRules(String date, String reason) {
        assertThat(map(line(4, date, "SHOP", "1", "", "AMD", "")).problems())
                .containsExactly(new RowProblem(4, reason));
    }

    @Test
    void textDatesMayCarryATimeAndUseIsoOrder() {
        ParseResult result = map(line(2, "01.05.2026 13:45", "A", "1", "", "AMD", ""),
                line(3, "2026-05-01", "B", "1", "", "AMD", ""),
                line(4, "01.05.2026 13:45:59", "C", "1", "", "AMD", ""));

        assertThat(result.rows()).extracting(ParsedRow::date).containsOnly(MAY_FIRST).hasSize(3);
    }

    @Test
    void cellTypesThatCannotBeADateOrAmountAreProblems() {
        ParseResult result = map(
                line(2, new Cell.Numeric(46143, "46143"), text("A"), text("1"), Cell.BLANK, text("AMD")),
                line(3, Cell.INVALID, text("A"), text("1"), Cell.BLANK, text("AMD")),
                line(4, new Cell.DateCell(MAY_FIRST), text("A"), new Cell.DateCell(MAY_FIRST), Cell.BLANK, text("AMD")),
                line(5, new Cell.DateCell(MAY_FIRST), text("A"), Cell.INVALID, Cell.BLANK, text("AMD")));

        assertThat(result.problems()).extracting(RowProblem::reason).containsExactly(
                "date is a number, not a date",
                "cell has an error value in Date column",
                "unreadable amount in Debit column",
                "cell has an error value in Debit column");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "֏", "$", ""})
    void unsupportedCurrencyNamesTheSupportedOnesWithoutEchoingTheCell(String currency) {
        assertThat(map(line(9, "01.05.2026", "SHOP", "1", "", currency, "")).problems())
                .containsExactly(new RowProblem(9, "unsupported currency; supported: AMD, USD"));
    }

    @Test
    void checksRunInOrderSoEachLineGetsOneProblem() {
        ParseResult result = map(line(2, "bad", "", "x", "y", "EUR", ""));

        assertThat(result.problems()).containsExactly(
                new RowProblem(2, "unreadable date; expected dd.mm.yyyy or yyyy-mm-dd"));
    }

    @Test
    void descriptionIsCollapsedAndMaskedButKeepsItsCaseAndArmenianLetters() {
        String cardNumber = "4578" + "1234".repeat(3);
        String split = cardNumber.substring(0, 8) + "\t" + cardNumber.substring(8);
        ParseResult result = map(
                line(2, "01.05.2026", " Pos  " + split + "\nԵրևան  ", "1", "", "AMD", ""),
                line(3, "01.05.2026", "SAS 4578-1234-1234-1234", "1", "", "AMD", ""),
                line(4, "01.05.2026", "\t \n", "1", "", "AMD", ""));

        assertThat(result.rows()).extracting(ParsedRow::description)
                .containsExactly("Pos ****1234 Երևան", "SAS ****1234");
        assertThat(result.problems()).containsExactly(new RowProblem(4, "missing description"));
    }

    @Test
    void invisibleFormatCharactersAreRemovedSoASplitCardNumberIsStillMasked() {
        String cardNumber = "4578" + "1234".repeat(3);
        String hidden = cardNumber.substring(0, 6) + "\u200B" + cardNumber.substring(6, 10) + "\u202E"
                + cardNumber.substring(10);

        ParseResult result = map(line(2, "01.05.2026", "POS " + hidden + " SHOP\u200D", "1", "", "AMD", ""));

        assertThat(result.rows()).extracting(ParsedRow::description).containsExactly("POS ****1234 SHOP");
    }

    @Test
    void theDoubleToleranceHoldsForLargeAmounts() {
        ParseResult result = map(
                row(2, new Cell.Numeric(12_345_678_901.23, ""), Cell.BLANK),
                row(3, new Cell.Numeric(999_999_999_999.99, ""), Cell.BLANK),
                row(4, new Cell.Numeric(1_000_000.0000001, ""), Cell.BLANK),
                row(5, new Cell.Numeric(1_000_000.001, ""), Cell.BLANK),
                row(6, new Cell.Numeric(12_345_678_901.235, ""), Cell.BLANK));

        assertThat(result.rows()).extracting(ParsedRow::money)
                .containsExactly(amd("12345678901.23"), amd("999999999999.99"), amd("1000000.00"));
        assertThat(result.problems()).extracting(RowProblem::lineNumber).containsExactly(5, 6);
    }

    @Test
    void numericDescriptionsUseTheirFormattedText() {
        ParseResult result = map(line(2, new Cell.DateCell(MAY_FIRST), new Cell.Numeric(12345, "12345"), text("1"),
                Cell.BLANK, text("AMD")));

        assertThat(result.rows()).extracting(ParsedRow::description).containsExactly("12345");
    }

    @Test
    void widthMustMatchTheHeaderButTrailingBlankCellsAreFine() {
        ParseResult result = map(line(2, "01.05.2026", "SHOP", "1", "AMD"),
                line(3, "01.05.2026", "SHOP", "1", "", "AMD", "", "", ""),
                line(4, "01.05.2026", "SHOP", "1", "", "AMD", "", "x"));

        assertThat(result.rows()).extracting(ParsedRow::lineNumber).containsExactly(3);
        assertThat(result.problems()).containsExactly(new RowProblem(2, "row has 4 columns, header has 6"),
                new RowProblem(4, "row has 7 columns, header has 6"));
    }

    @Test
    void stopsAtTheTotalsLineAndIgnoresTotalsAndBalanceLinesAfterIt() {
        ParseResult result = map(valid(2), line(3, "Total", "", "10", "20", "", ""),
                line(4, "", "Closing balance", "", "", "", "100"), line(5, "Ընդամենը", "", "", "", "", ""));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.problems()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void warnsOnceAboutDataAfterTheTotalsLineWithoutCountingItAsRejected() {
        ParseResult result = map(valid(2), line(3, "ИТОГО:", "", "", "", "", ""),
                line(4, "Account USD", "", "", "", "", ""),
                line(5, "Date", "Description", "Debit", "Credit", "Currency", "Balance"), valid(6), valid(7),
                line(8, "Сальдо", "", "", "", "", ""));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.problems()).isEmpty();
        assertThat(result.warnings()).containsExactly(new FileWarning(FileWarning.Code.ROWS_AFTER_TOTALS,
                "4 rows after the totals line (from line 4) were not imported", 4));
    }

    @Test
    void aBadRowDoesNotStopTheRowsAroundIt() {
        ParseResult result = map(valid(2), line(3, "01.05.2026", "SHOP", "", "", "AMD", ""), valid(4));

        assertThat(result.rows()).extracting(ParsedRow::lineNumber).containsExactly(2, 4);
        assertThat(result.problems()).extracting(RowProblem::lineNumber).containsExactly(3);
    }

    private ParseResult map(SourceLine... lines) {
        return mapper.map(List.of(lines));
    }

    private static SourceLine valid(int lineNumber) {
        return line(lineNumber, "01.05.2026", "SHOP", "1", "", "AMD", "");
    }

    private static SourceLine row(int lineNumber, Cell debit, Cell credit) {
        return line(lineNumber, new Cell.DateCell(MAY_FIRST), text("SHOP"), debit, credit, text("AMD"), Cell.BLANK);
    }

    private static SourceLine line(int lineNumber, String... cells) {
        return new SourceLine(lineNumber, Stream.of(cells).map(Cell::ofText).toList());
    }

    private static SourceLine line(int lineNumber, Cell... cells) {
        List<Cell> padded = new ArrayList<>(List.of(cells));
        while (padded.size() < HEADER.cells().size()) {
            padded.add(Cell.BLANK);
        }
        return new SourceLine(lineNumber, padded);
    }

    private static Cell text(String value) {
        return Cell.ofText(value);
    }

    private static Money amd(String amount) {
        return new Money(new BigDecimal(amount), Currency.AMD);
    }
}
