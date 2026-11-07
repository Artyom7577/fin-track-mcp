package com.artyomh.finance.adapter.parser.format;

import com.artyomh.finance.adapter.parser.XlsxFixture;
import com.artyomh.finance.adapter.parser.exception.ReadFailure;
import com.artyomh.finance.adapter.parser.exception.StatementReadException;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class XlsxFormatReaderTest {

    private static final Predicate<List<String>> HEADER = cells -> cells.contains("Date");
    private static final LocalDate MAY_FIRST = LocalDate.of(2026, Month.MAY, 1);
    private static final int DAYS_BETWEEN_1900_AND_1904_SYSTEMS = 1462;

    @TempDir
    private Path dir;

    private final XlsxFormatReader reader = new XlsxFormatReader(100, 1_000_000);

    @Test
    void readsEachCellAsItsStoredType() {
        SourceSheet sheet = read(new XlsxFixture().sheet("Statement", List.of(
                row("Date", "Number", "Text", "Blank", "Flag", "Error", "Cached", "Cached text"),
                row(MAY_FIRST, new XlsxFixture.Plain(12345), "SHOP", "  ", true,
                        new XlsxFixture.Error(FormulaError.NA), new XlsxFixture.Formula("1+1", 2.5),
                        new XlsxFixture.Formula("\"A\"&\"B\"", "AB")))));

        assertThat(sheet.lines().getFirst().cells()).containsExactly(
                new Cell.DateCell(MAY_FIRST), new Cell.Numeric(12345, "12345"), new Cell.Text("SHOP"), Cell.BLANK,
                Cell.INVALID, Cell.INVALID, new Cell.Numeric(2.5, "2.50"), new Cell.Text("AB"));
    }

    @Test
    void findsAHeaderBelowATitleBlockSkipsBlankRowsAndKeepsRowNumbers() {
        SourceSheet sheet = read(new XlsxFixture().sheet("Statement", List.of(
                row("Statement 2026"), row(), row("Date", "Description"), row(MAY_FIRST, "A"), row(), row(" "),
                row(MAY_FIRST, "B"))));

        assertThat(sheet.header().lineNumber()).isEqualTo(3);
        assertThat(sheet.lines()).extracting(SourceLine::lineNumber).containsExactly(4, 7);
    }

    @Test
    void dataLinesHaveExactlyTheHeaderWidth() {
        SourceSheet sheet = read(new XlsxFixture().sheet("Statement", List.of(
                row("Date", "Description"), row(MAY_FIRST), row(MAY_FIRST, "A", "note right of the table"))));

        assertThat(sheet.lines()).allSatisfy(line -> assertThat(line.cells()).hasSize(2));
    }

    @Test
    void namesOtherSheetsThatHoldData() {
        SourceSheet sheet = read(new XlsxFixture()
                .sheet("AMD", List.of(row("Date")))
                .sheet("Empty", List.of(row(), row(" ")))
                .sheet("USD", List.of(row("Date"), row(MAY_FIRST))));

        assertThat(sheet.unreadSheets()).containsExactly(new UnreadSheet(3, "USD", 2));
    }

    @Test
    void noHeaderInTheFirstTwentyRowsIsEmpty() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < FormatReader.HEADER_SEARCH_LINES; i++) {
            rows.add(row("title"));
        }
        rows.add(row("Date"));

        assertThat(reader.read(new XlsxFixture().sheet("S", rows).writeTo(dir.resolve("late.xlsx")), HEADER))
                .isEmpty();
    }

    @Test
    void stopsAsSoonAsTheRowLimitIsExceeded() {
        Path file = new XlsxFixture().sheet("S", List.of(row("Date"), row(MAY_FIRST), row(MAY_FIRST), row(MAY_FIRST)))
                .writeTo(dir.resolve("big.xlsx"));

        assertThatExceptionOfType(StatementReadException.class)
                .isThrownBy(() -> new XlsxFormatReader(2, 1_000_000).read(file, HEADER))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.TOO_MANY_ROWS));
    }

    @Test
    void aZipThatIsNotAWorkbookIsNotReadable() throws IOException {
        Path file = dir.resolve("fake.xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("hello.txt"));
            zip.write("hi".getBytes(StandardCharsets.UTF_8));
        }

        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> reader.read(file, HEADER))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.FILE_DAMAGED))
                .withMessageStartingWith("fake.xlsx is damaged");
    }

    @Test
    void anExternalEntityInSharedStringsIsNeverResolved() throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET");
        Path file = withSharedStrings(new XlsxFixture().sheet("S", List.of(row("Date"), row("x")))
                .writeTo(dir.resolve("plain.xlsx")), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE sst [<!ENTITY xxe SYSTEM "%s">]>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">\
                <si><t>Date</t></si><si><t>&xxe;</t></si></sst>""".formatted(secret.toUri()));

        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> reader.read(file, HEADER))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.FILE_DAMAGED))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("TOP-SECRET"));
    }

    @Test
    void rejectsSheetXmlThatUnpacksPastTheLimitBeforePoiLoadsIt() {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("Date", "Description"));
        for (int i = 0; i < 2_000; i++) {
            rows.add(row("01.05.2026", "SHOP"));
        }
        Path file = new XlsxFixture().sheet("S", rows).writeTo(dir.resolve("repetitive.xlsx"));

        assertThat(new XlsxFormatReader(100_000, 1_000_000).read(file, HEADER)).isPresent();
        assertThatExceptionOfType(StatementReadException.class)
                .isThrownBy(() -> new XlsxFormatReader(100_000, 50_000).read(file, HEADER))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.FILE_TOO_LARGE))
                .withMessage("The contents of repetitive.xlsx unpack to more than 0.0 MB, the limit. "
                        + "Split the export by period.");
    }

    @Test
    void rejectsMoreXmlElementsThanTheRowLimitCanHoldBeforePoiLoadsThem() {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("Date"));
        for (int i = 0; i < 300; i++) {
            rows.add(row(new XlsxFixture.Plain(1)));
        }
        Path file = new XlsxFixture().sheet("S", rows).writeTo(dir.resolve("dense.xlsx"));

        assertThatExceptionOfType(StatementReadException.class)
                .isThrownBy(() -> new XlsxFormatReader(10, 10_000_000).read(file, HEADER))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.TOO_MANY_ROWS))
                .withMessage("dense.xlsx holds more cells than 10 statement rows; split the export by period and "
                        + "import each part.");
    }

    @Test
    void bulkHiddenInAPartOtherThanTheSheetCountsTowardTheLimit() throws IOException {
        Map<String, String> parts = parts(new XlsxFixture().sheet("S", List.of(row("Date"), row(MAY_FIRST)))
                .writeTo(dir.resolve("small.xlsx")));
        String styles = parts.get("xl/styles.xml");
        int afterDeclaration = styles.indexOf("?>") + 2;
        // compresses well under POI's own 100x ratio guard, so only the unpacked count can catch it
        parts.put("xl/styles.xml", styles.substring(0, afterDeclaration) + "<!--" + padding(2_000_000) + "-->"
                + styles.substring(afterDeclaration));
        Path file = zip(dir.resolve("padded-styles.xlsx"), parts);

        assertThat(new XlsxFormatReader(100, 10_000_000).read(file, HEADER)).isPresent();
        assertThatExceptionOfType(StatementReadException.class)
                .isThrownBy(() -> new XlsxFormatReader(100, 1_000_000).read(file, HEADER))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.FILE_TOO_LARGE));
    }

    @Test
    void theSheetIsFoundThroughTheWorkbookRelationshipsNotAFixedPath() throws IOException {
        Map<String, String> parts = parts(new XlsxFixture().sheet("S", List.of(row("Date"), row(MAY_FIRST)))
                .writeTo(dir.resolve("standard.xlsx")));
        Map<String, String> moved = new LinkedHashMap<>();
        parts.forEach((name, text) -> moved.put(name.replace("xl/worksheets/sheet1.xml", "xl/statement/data.xml"),
                text.replace("worksheets/sheet1.xml", "statement/data.xml")));
        Path file = zip(dir.resolve("moved.xlsx"), moved);

        assertThat(moved).containsKey("xl/statement/data.xml").doesNotContainKey("xl/worksheets/sheet1.xml");
        assertThat(reader.read(file, HEADER).orElseThrow().lines()).singleElement()
                .extracting(line -> line.cell(0)).isEqualTo(new Cell.DateCell(MAY_FIRST));
    }

    @Test
    void readsDateSerialsInA1904Workbook() {
        double serial1904 = DateUtil.getExcelDate(MAY_FIRST.atStartOfDay()) - DAYS_BETWEEN_1900_AND_1904_SYSTEMS;

        SourceSheet sheet = reader.read(new XlsxFixture().date1904()
                .sheet("S", List.of(row("Date"), row(new XlsxFixture.DateSerial(serial1904))))
                .writeTo(dir.resolve("mac.xlsx")), HEADER).orElseThrow();

        assertThat(sheet.lines().getFirst().cell(0)).isEqualTo(new Cell.DateCell(MAY_FIRST));
    }

    @Test
    void aMergedCellHasItsValueOnlyInItsFirstCell() {
        SourceSheet sheet = reader.read(new XlsxFixture()
                .sheet("S", List.of(row("Statement"), row("Date", "Description"), row(MAY_FIRST, "A"), row(null, "B")))
                .merge("S", "A1:B1")
                .merge("S", "A3:A4")
                .writeTo(dir.resolve("merged.xlsx")), HEADER).orElseThrow();

        assertThat(sheet.header().lineNumber()).isEqualTo(2);
        assertThat(sheet.lines()).extracting(line -> line.cell(0))
                .containsExactly(new Cell.DateCell(MAY_FIRST), Cell.BLANK);
    }

    private Path withSharedStrings(Path source, String sharedStrings) throws IOException {
        Map<String, String> parts = parts(source);
        parts.put("xl/sharedStrings.xml", sharedStrings);
        return zip(dir.resolve("xxe.xlsx"), parts);
    }

    private static String padding(int length) {
        Random random = new Random(1);
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append((char) ('a' + random.nextInt(4)));
        }
        return text.toString();
    }

    // a workbook's parts as text, in zip order, so a test can rewrite one and zip them again
    private static Map<String, String> parts(Path workbook) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(workbook.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                parts.put(entry.getName(), new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static Path zip(Path target, Map<String, String> parts) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                out.putNextEntry(new ZipEntry(part.getKey()));
                out.write(part.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return target;
    }

    private SourceSheet read(XlsxFixture fixture) {
        return reader.read(fixture.writeTo(dir.resolve("statement.xlsx")), HEADER).orElseThrow();
    }

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }
}
