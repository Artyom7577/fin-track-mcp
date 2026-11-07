package com.artyomh.finance.adapter.parser;

import com.artyomh.finance.adapter.parser.exception.ReadFailure;
import com.artyomh.finance.adapter.parser.exception.StatementReadException;
import com.artyomh.finance.application.importing.FileWarning;
import com.artyomh.finance.application.importing.ParseResult;
import com.artyomh.finance.application.importing.ParsedRow;
import com.artyomh.finance.application.importing.RowProblem;
import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.mockdata.generator.GeneratedRow;
import com.artyomh.finance.mockdata.generator.GeneratedStatement;
import com.artyomh.finance.mockdata.generator.MalformedRow;
import com.artyomh.finance.mockdata.generator.StatementGenerator;
import com.artyomh.finance.mockdata.generator.StatementLine;
import com.artyomh.finance.mockdata.scenario.Malformation;
import com.artyomh.finance.mockdata.scenario.Scenario;
import com.artyomh.finance.mockdata.scenario.Scenarios;
import com.artyomh.finance.mockdata.writer.CsvStatementWriter;
import com.artyomh.finance.mockdata.writer.StatementWriter;
import com.artyomh.finance.mockdata.writer.XlsxStatementWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.random.RandomGeneratorFactory;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

class StatementFileParserTest {

    private static final long SEED = 20260501L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
    private static final int HEADER_LINES = 1;
    private static final LocalDate MAY_FIRST = LocalDate.of(2026, Month.MAY, 1);

    @TempDir
    private Path dir;

    private final StatementFileParser parser = new StatementFileParser(ParserLimits.DEFAULT);

    static List<StatementWriter> writers() {
        return List.of(new CsvStatementWriter(), new XlsxStatementWriter(CLOCK));
    }

    @ParameterizedTest
    @MethodSource("writers")
    void roundTripsTheDefaultScenarioIgnoringBalance(StatementWriter writer) throws IOException {
        GeneratedStatement statement = generate(Scenarios.defaultSixMonths());

        ParseResult result = parser.parse(write(writer, statement));

        assertThat(result.problems()).isEmpty();
        assertThat(result.rows()).containsExactlyElementsOf(expectedRows(statement));
    }

    @ParameterizedTest
    @MethodSource("writers")
    void eachMalformedRowGivesOneProblemOnItsLineAndTheRowsAroundItStillParse(StatementWriter writer)
            throws IOException {
        GeneratedStatement statement = generate(Scenarios.withMalformedRows());

        ParseResult result = parser.parse(write(writer, statement));

        List<Integer> malformedLines = IntStream.range(0, statement.lines().size())
                .filter(i -> statement.lines().get(i) instanceof MalformedRow)
                .mapToObj(i -> i + HEADER_LINES + 1)
                .toList();
        assertThat(result.problems()).extracting(RowProblem::lineNumber)
                .containsExactlyElementsOf(malformedLines)
                .hasSize(Malformation.values().length);
        assertThat(result.problems()).extracting(RowProblem::reason).containsExactlyInAnyOrder(
                "unreadable date; expected dd.mm.yyyy or yyyy-mm-dd",
                "unreadable amount in Debit column",
                "no amount in Debit or Credit",
                "unsupported currency; supported: AMD, USD");
        assertThat(result.rows()).containsExactlyElementsOf(expectedRows(statement));
    }

    @ParameterizedTest
    @MethodSource("writers")
    void resultDoesNotDependOnDefaultLocaleOrTimeZone(StatementWriter writer) throws IOException {
        Path file = write(writer, generate(Scenarios.withMalformedRows()));
        ParseResult reference = parser.parse(file);
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.of("hy", "AM"));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yerevan"));
            assertThat(parser.parse(file)).isEqualTo(reference);
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
    }

    @ParameterizedTest
    @MethodSource("writers")
    void masksAFullCardNumberBeforeItReachesARowOrAProblem(StatementWriter writer) throws IOException {
        // built at runtime so no card-number-shaped literal sits in the repository for scanners to flag
        String cardNumber = "4578" + "1234".repeat(3);
        Money amount = new Money(new BigDecimal("1800.00"), Currency.AMD);
        GeneratedStatement statement = new GeneratedStatement(List.of(
                new GeneratedRow(MAY_FIRST, "POS " + cardNumber + " SHOP", Direction.EXPENSE, amount, BigDecimal.ONE),
                new MalformedRow("bad", "POS " + cardNumber + " SHOP", "1", "", "AMD")));

        ParseResult result = parser.parse(write(writer, statement));

        assertThat(result.rows()).extracting(ParsedRow::description).containsExactly("POS ****1234 SHOP");
        assertThat(result.problems()).singleElement()
                .satisfies(problem -> assertThat(problem.reason()).doesNotContain(cardNumber, "1234"));
    }

    @Test
    void aMissingFileOrADirectoryIsNotReadable() {
        assertFails(dir.resolve("missing.csv"), ReadFailure.FILE_NOT_READABLE);
        assertFails(dir, ReadFailure.FILE_NOT_READABLE);
    }

    @Test
    void anEmptyFileFailsBeforeAnythingElse() throws IOException {
        assertFails(Files.createFile(dir.resolve("empty.pdf")), ReadFailure.FILE_EMPTY);
    }

    @Test
    void aFileOverTheSizeLimitFailsWithLocaleFreeMegabytes() throws IOException {
        Path file = Files.write(dir.resolve("big.csv"), new byte[2_345_678]);
        StatementFileParser strict = new StatementFileParser(new ParserLimits(1_000_000, 1_000_000, 1_000_000, 10));
        Locale locale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> strict.parse(file))
                    .satisfies(e -> assertThat(e.code()).isEqualTo("FILE_TOO_LARGE"))
                    .withMessage("File is 2.4 MB; the limit for .csv is 1.0 MB. Split the export by period.");
        } finally {
            Locale.setDefault(locale);
        }
    }

    @Test
    void recognizesAnOldXlsFileByItsFirstBytes() throws IOException {
        Path file = Files.write(dir.resolve("statement.xlsx"),
                new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});

        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> parser.parse(file))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.LEGACY_XLS))
                .withMessage("Old .xls format is not supported. Open it in Excel and save as .xlsx.");
    }

    @Test
    void recognizesAWebPageAfterAByteOrderMarkAndWhitespace() throws IOException {
        Path doctype = Files.writeString(dir.resolve("statement.xlsx"), "﻿ \r\n\t<!DOCTYPE html><html></html>");
        Path html = Files.writeString(dir.resolve("statement.csv"), "<HTML><body>Date</body></HTML>");

        assertFails(doctype, ReadFailure.WEB_PAGE);
        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> parser.parse(html))
                .withMessage("This file is a web page, not a spreadsheet. Export as .xlsx or .csv.");
    }

    @Test
    void theExtensionMustAgreeWithTheFirstBytes() throws IOException {
        Path workbook = write(new XlsxStatementWriter(CLOCK), generate(Scenarios.goldenSample()));
        Path renamedWorkbook = Files.copy(workbook, dir.resolve("statement.csv"));
        Path textAsXlsx = Files.writeString(dir.resolve("text.XLSX"), "Date,Description\n");
        Path text = Files.writeString(dir.resolve("statement.txt"), "Date,Description\n");

        assertFails(renamedWorkbook, ReadFailure.EXTENSION_MISMATCH);
        assertFails(textAsXlsx, ReadFailure.EXTENSION_MISMATCH);
        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> parser.parse(text))
                .withMessage("statement.txt is not a valid .xlsx or .csv file. Supported: Evoca .xlsx or .csv export.");
    }

    @Test
    void recognizesAPdfByItsFirstBytesWhateverItsName() throws IOException {
        Path pdf = Files.writeString(dir.resolve("statement.pdf"), "%PDF-1.7\n");
        Path renamed = Files.writeString(dir.resolve("statement.csv"), "%PDF-1.7\n");

        assertFails(pdf, ReadFailure.PDF_NOT_SUPPORTED);
        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> parser.parse(renamed))
                .withMessage("PDF statements are not supported. Export as .xlsx or .csv.");
    }

    @Test
    void aZipThatIsNotAWorkbookIsDamagedNotMissing() throws IOException {
        Path file = dir.resolve("statement.xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("hello.txt"));
            zip.write(1);
        }

        assertFails(file, ReadFailure.FILE_DAMAGED);
    }

    @Test
    void aNearEvocaHeaderFromAnotherBankIsNotAccepted() throws IOException {
        Path signedAmount = Files.writeString(dir.resolve("ameria.csv"),
                "Date,Description,Amount,Currency,Balance\n01.05.2026,SHOP,-1500.00,AMD,10.00\n");
        Path otherLabels = Files.writeString(dir.resolve("other.csv"),
                "Transaction date,Details,Debit,Credit,Currency\n01.05.2026,SHOP,1500.00,,AMD\n");

        assertFails(signedAmount, ReadFailure.HEADER_NOT_FOUND);
        assertFails(otherLabels, ReadFailure.HEADER_NOT_FOUND);
    }

    @Test
    void theExtensionCheckIgnoresCase() throws Exception {
        Path file = Files.copy(irregular("title-block.csv"), dir.resolve("TITLE.CSV"));

        assertThat(parser.parse(file).rows()).hasSize(5);
    }

    @Test
    void noHeaderListsTheExpectedColumns() throws IOException {
        Path file = Files.writeString(dir.resolve("other.csv"), "When,What,Amount\n01.05.2026,SHOP,1\n");

        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> parser.parse(file))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.HEADER_NOT_FOUND))
                .withMessage("No statement header found in the first 20 rows of other.csv. Expected columns: "
                        + "Date (Ամսաթիվ), Description (Նկարագրություն), Debit (Ելք), Credit (Մուտք), "
                        + "Currency (Արժույթ).");
    }

    @Test
    void aHeaderOnlyFileParsesToNothing() throws IOException {
        Path file = Files.writeString(dir.resolve("header-only.csv"), "Date,Description,Debit,Credit,Currency\r\n\r\n");

        ParseResult result = parser.parse(file);

        assertThat(result.rows()).isEmpty();
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void readsAHeaderBelowATitleBlock() throws Exception {
        ParseResult result = parser.parse(irregular("title-block.csv"));

        assertThat(result.problems()).isEmpty();
        assertThat(result.rows()).extracting(ParsedRow::lineNumber).containsExactly(6, 7, 8, 9, 10);
        assertThat(result.rows()).element(3).isEqualTo(new ParsedRow(9, LocalDate.of(2026, Month.MAY, 9),
                "POS 4578****1234 COFFEESHOP COMPANY, YEREVAN AM", Direction.EXPENSE, amd("1800")));
        assertThat(result.rows()).element(2).extracting(ParsedRow::money)
                .isEqualTo(new Money(new BigDecimal("9.99"), Currency.USD));
    }

    @Test
    void aWorkbookSavedByLibreOfficeParsesToTheSameRowsAsItsCsv() throws Exception {
        ParseResult fromCsv = parser.parse(irregular("title-block.csv"));

        assertThat(parser.parse(irregular("libreoffice-saved.xlsx"))).isEqualTo(fromCsv);
    }

    @Test
    void reportsRowsAfterTheTotalsLineInsteadOfDroppingThem() throws Exception {
        ParseResult result = parser.parse(irregular("totals-then-usd-block.csv"));

        assertThat(result.rows()).extracting(ParsedRow::lineNumber).containsExactly(2, 3);
        assertThat(result.problems()).isEmpty();
        assertThat(result.warnings()).containsExactly(new FileWarning(FileWarning.Code.ROWS_AFTER_TOTALS,
                "3 rows after the totals line (from line 7) were not imported", 3));
    }

    @Test
    void readsASemicolonCsvWithSpaceGroupedAmounts() throws Exception {
        ParseResult result = parser.parse(irregular("semicolon.csv"));

        assertThat(result.problems()).isEmpty();
        assertThat(result.rows()).extracting(ParsedRow::description, ParsedRow::money).containsExactly(
                tuple("TRANSFER SALARY; ACME LLC", amd("850000")),
                tuple("POS 4578****1234 SAS SUPERMARKET YEREVAN AM", amd("12340")));
    }

    @Test
    void blankTrailingLinesAreNeitherRowsNorProblems() throws Exception {
        ParseResult result = parser.parse(irregular("blank-trailing-lines.csv"));

        assertThat(result.rows()).extracting(ParsedRow::lineNumber).containsExactly(2, 4);
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void aQuotedLineBreakBecomesASpaceAndLaterLinesKeepTheirNumbers() throws Exception {
        ParseResult result = parser.parse(irregular("quoted-multiline.csv"));

        assertThat(result.rows()).extracting(ParsedRow::lineNumber, ParsedRow::description).containsExactly(
                tuple(2, "POS 4578****1234 SAS SUPERMARKET YEREVAN AM"),
                tuple(4, "BOOKING.COM \"HOTEL\""));
    }

    @Test
    void anUnclosedQuoteFailsTheWholeFile() throws Exception {
        assertThatExceptionOfType(StatementReadException.class)
                .isThrownBy(() -> parser.parse(irregular("unclosed-quote.csv")))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.MALFORMED_CSV))
                .withMessage("Unclosed quote starting at line 3. Fix the file and import again.");
    }

    @Test
    void aHandMadeWorkbookWithTitleTotalsFormulasAndAnotherSheet() {
        String cardNumber = "4578" + "1234".repeat(3);
        Path file = new XlsxFixture()
                .sheet("AMD", List.of(
                        List.of("Evoca Bank account statement"),
                        List.of(),
                        List.of("Ամսաթիվ / Date", "Նկարագրություն / Description", "Ելք / Debit", "Մուտք / Credit",
                                "Արժույթ / Currency"),
                        List.of(MAY_FIRST, "TRANSFER SALARY ACME LLC", "", 850000, "AMD"),
                        List.of(new XlsxFixture.Formula("DATE(2026,5,3)", MAY_FIRST.plusDays(2)), "RENT",
                                new XlsxFixture.Formula("200000+50000", 250000), "", "AMD"),
                        List.of(),
                        List.of("Total", "", 250000, 850000)))
                .sheet("\u200B" + cardNumber, List.of(List.of("Date"), List.of(), List.of(MAY_FIRST)))
                .writeTo(dir.resolve("hand-made.xlsx"));

        ParseResult result = parser.parse(file);

        assertThat(result.rows()).containsExactly(
                new ParsedRow(4, MAY_FIRST, "TRANSFER SALARY ACME LLC", Direction.INCOME, amd("850000")),
                new ParsedRow(5, MAY_FIRST.plusDays(2), "RENT", Direction.EXPENSE, amd("250000")));
        assertThat(result.problems()).isEmpty();
        assertThat(result.warnings()).containsExactly(new FileWarning(FileWarning.Code.SHEET_NOT_READ,
                "sheet 2 ('****1234') was not imported", 2));
    }

    @Test
    void fiftyThousandRowFilesFitUnderEveryLimit() throws IOException {
        GeneratedStatement statement = maximalStatement();
        Path workbook = write(new XlsxStatementWriter(CLOCK), statement);
        Path csv = write(new CsvStatementWriter(), statement);

        assertThat(Files.size(workbook)).isLessThan(ParserLimits.DEFAULT.maxXlsxBytes());
        assertThat(Files.size(csv)).isLessThan(ParserLimits.DEFAULT.maxCsvBytes());
        assertThat(parser.parse(workbook).rows()).hasSize(ParserLimits.DEFAULT.maxDataRows());
        assertThat(parser.parse(csv).rows()).hasSize(ParserLimits.DEFAULT.maxDataRows());
    }

    // the default scenario repeated to the row limit, so size and memory reflect realistic descriptions
    private static GeneratedStatement maximalStatement() {
        List<GeneratedRow> base = new StatementGenerator().generate(Scenarios.defaultSixMonths(),
                RandomGeneratorFactory.of("L64X128MixRandom").create(SEED)).rows();
        List<StatementLine> lines = new ArrayList<>(ParserLimits.DEFAULT.maxDataRows());
        for (int i = 0; i < ParserLimits.DEFAULT.maxDataRows(); i++) {
            lines.add(base.get(i % base.size()));
        }
        return new GeneratedStatement(lines);
    }

    private void assertFails(Path file, ReadFailure failure) {
        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> parser.parse(file))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(failure));
    }

    private static List<ParsedRow> expectedRows(GeneratedStatement statement) {
        List<ParsedRow> rows = new ArrayList<>();
        List<StatementLine> lines = statement.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) instanceof GeneratedRow row) {
                rows.add(new ParsedRow(i + HEADER_LINES + 1, row.date(), row.description(), row.direction(),
                        row.money()));
            }
        }
        return rows;
    }

    private static GeneratedStatement generate(Scenario scenario) {
        return new StatementGenerator().generate(scenario, RandomGeneratorFactory.of("L64X128MixRandom").create(SEED));
    }

    private Path write(StatementWriter writer, GeneratedStatement statement) throws IOException {
        Path file = dir.resolve("statement." + writer.fileExtension());
        try (OutputStream out = Files.newOutputStream(file)) {
            writer.write(statement, out);
        }
        return file;
    }

    private static Path irregular(String name) throws URISyntaxException {
        return Path.of(StatementFileParserTest.class.getResource("/irregular/" + name).toURI());
    }

    private static Money amd(String amount) {
        return new Money(new BigDecimal(amount), Currency.AMD);
    }
}
