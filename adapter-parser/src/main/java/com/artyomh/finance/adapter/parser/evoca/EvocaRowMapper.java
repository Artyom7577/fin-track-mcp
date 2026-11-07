package com.artyomh.finance.adapter.parser.evoca;

import com.artyomh.finance.adapter.parser.evoca.EvocaColumns.Column;
import com.artyomh.finance.adapter.parser.format.Cell;
import com.artyomh.finance.adapter.parser.format.SourceLine;
import com.artyomh.finance.application.importing.FileWarning;
import com.artyomh.finance.application.importing.ParseResult;
import com.artyomh.finance.application.importing.ParsedRow;
import com.artyomh.finance.application.importing.RowProblem;
import com.artyomh.finance.domain.merchant.CardNumberMasker;
import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns the lines below an Evoca header into rows or problems, one problem per line, never echoing a cell value.
 */
public final class EvocaRowMapper {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT));
    private static final Pattern DATE_WITH_OPTIONAL_TIME = Pattern.compile("(\\S+)(?: \\d{1,2}:\\d{2}(?::\\d{2})?)?");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cc}");
    private static final Pattern FORMAT_CHARACTERS = Pattern.compile("\\p{Cf}");
    private static final Pattern WHITESPACE_RUNS = Pattern.compile("[\\s\\p{Z}]+");
    private static final BigDecimal DOUBLE_TOLERANCE = new BigDecimal("0.000001");
    private static final List<String> TOTALS_WORDS = List.of("total", "ընդամենը", "итого", "всего");
    private static final List<String> BALANCE_WORDS = List.of("balance", "մնացորդ", "остаток", "сальдо");
    private static final String SUPPORTED_CURRENCIES = Arrays.stream(Currency.values())
            .map(Currency::name)
            .collect(Collectors.joining(", "));

    private final EvocaColumns columns;
    private final int headerWidth;

    /**
     * A mapper for the columns of an accepted header line.
     */
    public EvocaRowMapper(SourceLine header) {
        this.columns = EvocaColumns.locate(header.texts())
                .orElseThrow(() -> new IllegalArgumentException("not an Evoca header, line " + header.lineNumber()));
        this.headerWidth = header.contentWidth();
    }

    public ParseResult map(List<SourceLine> lines) {
        List<ParsedRow> rows = new ArrayList<>();
        List<RowProblem> problems = new ArrayList<>();
        int index = 0;
        while (index < lines.size() && !isTotals(lines.get(index))) {
            mapLine(lines.get(index), rows, problems);
            index++;
        }
        List<FileWarning> warnings = new ArrayList<>();
        unreadAfterTotals(lines.subList(index, lines.size())).ifPresent(warnings::add);
        return new ParseResult(rows, problems, warnings);
    }

    private void mapLine(SourceLine line, Collection<ParsedRow> rows, Collection<RowProblem> problems) {
        try {
            rows.add(toRow(line));
        } catch (RowRejected rejected) {
            problems.add(new RowProblem(line.lineNumber(), rejected.getMessage()));
        }
    }

    // a second account block after the totals must never vanish silently
    private Optional<FileWarning> unreadAfterTotals(Collection<SourceLine> tail) {
        List<SourceLine> unread = tail.stream()
                .filter(line -> !isTotals(line) && !isBalance(line))
                .toList();
        if (unread.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FileWarning(FileWarning.Code.ROWS_AFTER_TOTALS, unread.size()
                + " rows after the totals line (from line " + unread.getFirst().lineNumber() + ") were not imported",
                unread.size()));
    }

    private ParsedRow toRow(SourceLine line) throws RowRejected {
        checkWidth(line);
        LocalDate date = date(line.cell(columns.position(Column.DATE)));
        String description = description(line.cell(columns.position(Column.DESCRIPTION)));
        Currency currency = currency(line.cell(columns.position(Column.CURRENCY)));
        Optional<BigDecimal> debit = amount(line.cell(columns.position(Column.DEBIT)), Column.DEBIT, currency);
        Optional<BigDecimal> credit = amount(line.cell(columns.position(Column.CREDIT)), Column.CREDIT, currency);
        Direction direction = direction(debit, credit);
        BigDecimal value = switch (direction) {
            case EXPENSE -> debit.orElseThrow();
            case INCOME -> credit.orElseThrow();
        };
        return new ParsedRow(line.lineNumber(), date, description, direction, new Money(value, currency));
    }

    private void checkWidth(SourceLine line) throws RowRejected {
        int size = line.cells().size();
        boolean extraData = line.contentWidth() > headerWidth;
        if (size < headerWidth || extraData) {
            throw new RowRejected("row has " + size + " columns, header has " + headerWidth);
        }
    }

    private static LocalDate date(Cell cell) throws RowRejected {
        return switch (cell) {
            case Cell.DateCell dateCell -> dateCell.value();
            case Cell.Text text -> parseDate(text.value());
            case Cell.Numeric _ -> throw new RowRejected("date is a number, not a date");
            case Cell.Blank _ -> throw new RowRejected("missing date");
            case Cell.Invalid _ -> throw errorValue(Column.DATE);
        };
    }

    private static LocalDate parseDate(String text) throws RowRejected {
        Matcher matcher = DATE_WITH_OPTIONAL_TIME.matcher(collapse(text));
        if (matcher.matches()) {
            for (DateTimeFormatter format : DATE_FORMATS) {
                try {
                    return LocalDate.parse(matcher.group(1), format);
                } catch (DateTimeParseException ignored) {
                    // try the next format
                }
            }
        }
        throw new RowRejected("unreadable date; expected dd.mm.yyyy or yyyy-mm-dd");
    }

    private static String description(Cell cell) throws RowRejected {
        String raw = switch (cell) {
            case Cell.Text text -> text.value();
            case Cell.Numeric numeric -> numeric.formatted();
            case Cell.DateCell dateCell -> dateCell.text();
            case Cell.Blank _ -> "";
            case Cell.Invalid _ -> throw errorValue(Column.DESCRIPTION);
        };
        // control characters become spaces and invisible format characters (zero-width, bidi) go before masking,
        // so a card number split by a tab, a line break or a zero-width space is still one number to the masker
        String visible = FORMAT_CHARACTERS.matcher(CONTROL_CHARACTERS.matcher(raw).replaceAll(" ")).replaceAll("");
        String description = CardNumberMasker.mask(collapse(visible));
        if (description.isEmpty()) {
            throw new RowRejected("missing description");
        }
        return description;
    }

    private static Currency currency(Cell cell) throws RowRejected {
        if (cell instanceof Cell.Invalid) {
            throw errorValue(Column.CURRENCY);
        }
        String code = collapse(cell.text()).toUpperCase(Locale.ROOT);
        for (Currency currency : Currency.values()) {
            if (currency.name().equals(code)) {
                return currency;
            }
        }
        throw new RowRejected("unsupported currency; supported: " + SUPPORTED_CURRENCIES);
    }

    private static Optional<BigDecimal> amount(Cell cell, Column column, Currency currency) throws RowRejected {
        Optional<BigDecimal> amount = switch (cell) {
            case Cell.Numeric numeric -> Optional.of(fromDouble(numeric.value(), column, currency));
            case Cell.Text text -> Optional.of(fromText(text.value(), column, currency));
            case Cell.DateCell _ -> throw new RowRejected("unreadable amount in " + column.englishName() + " column");
            case Cell.Blank _ -> Optional.empty();
            case Cell.Invalid _ -> throw errorValue(column);
        };
        if (amount.isPresent() && amount.get().signum() < 0) {
            throw new RowRejected("negative amount in " + column.englishName() + " column");
        }
        return amount;
    }

    // Excel stores doubles: rounding absorbs 0.30000000000000004, the tolerance still catches a real 1234.567
    private static BigDecimal fromDouble(double value, Column column, Currency currency) throws RowRejected {
        BigDecimal exact = BigDecimal.valueOf(value);
        BigDecimal rounded = exact.setScale(currency.minorDigits(), RoundingMode.HALF_EVEN);
        if (exact.subtract(rounded).abs().compareTo(DOUBLE_TOLERANCE) > 0) {
            throw tooManyDecimals(column, currency);
        }
        return rounded;
    }

    private static BigDecimal fromText(String text, Column column, Currency currency) throws RowRejected {
        BigDecimal amount = AmountText.parse(text)
                .orElseThrow(() -> new RowRejected("unreadable amount in " + column.englishName() + " column"));
        if (amount.stripTrailingZeros().scale() > currency.minorDigits()) {
            throw tooManyDecimals(column, currency);
        }
        return amount.setScale(currency.minorDigits(), RoundingMode.UNNECESSARY);
    }

    // banks often print 0.00 in the column that does not apply, so a zero beside a positive amount counts as empty
    private static Direction direction(Optional<BigDecimal> debit, Optional<BigDecimal> credit) throws RowRejected {
        boolean hasDebit = debit.filter(amount -> amount.signum() > 0).isPresent();
        boolean hasCredit = credit.filter(amount -> amount.signum() > 0).isPresent();
        if (hasDebit && hasCredit) {
            throw new RowRejected("both Debit and Credit are filled");
        }
        if (hasDebit) {
            return Direction.EXPENSE;
        }
        if (hasCredit) {
            return Direction.INCOME;
        }
        if (debit.isPresent() || credit.isPresent()) {
            throw new RowRejected("amount is zero");
        }
        throw new RowRejected("no amount in Debit or Credit");
    }

    private boolean isTotals(SourceLine line) {
        String label = leadingLabel(line);
        return TOTALS_WORDS.stream().anyMatch(label::startsWith);
    }

    private boolean isBalance(SourceLine line) {
        List<String> words = List.of(leadingLabel(line).split(" "));
        return words.stream().anyMatch(word -> BALANCE_WORDS.stream().anyMatch(word::startsWith));
    }

    // the date cell, or the first non-empty cell when the date cell is empty, as a normalized label
    private String leadingLabel(SourceLine line) {
        Cell dateCell = line.cell(columns.position(Column.DATE));
        if (!(dateCell instanceof Cell.Blank)) {
            return EvocaColumns.normalizeLabel(dateCell.text());
        }
        return line.cells().stream()
                .filter(cell -> !(cell instanceof Cell.Blank))
                .findFirst()
                .map(cell -> EvocaColumns.normalizeLabel(cell.text()))
                .orElse("");
    }

    private static String collapse(String text) {
        return WHITESPACE_RUNS.matcher(text).replaceAll(" ").strip();
    }

    private static RowRejected errorValue(Column column) {
        return new RowRejected("cell has an error value in " + column.englishName() + " column");
    }

    private static RowRejected tooManyDecimals(Column column, Currency currency) {
        return new RowRejected("more than " + currency.minorDigits() + " decimal places in " + column.englishName()
                + " column");
    }

    /**
     * Ends the mapping of one line with its reason; never escapes this class.
     */
    private static final class RowRejected extends Exception {

        RowRejected(String reason) {
            super(reason, null, false, false);
        }
    }
}
