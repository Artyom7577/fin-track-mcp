package com.artyomh.finance.mockdata.writer;

import com.artyomh.finance.mockdata.generator.GeneratedStatement;
import com.artyomh.finance.mockdata.generator.MalformedRow;
import com.artyomh.finance.mockdata.generator.StatementGenerator;
import com.artyomh.finance.mockdata.generator.StatementLine;
import com.artyomh.finance.mockdata.scenario.Scenario;
import com.artyomh.finance.mockdata.scenario.Scenarios;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.random.RandomGeneratorFactory;

import static org.assertj.core.api.Assertions.assertThat;

class StatementWritersTest {

    private static final long SEED = 20260501L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
    private static final int HEADER_ROWS = 1;

    @TempDir
    private Path dir;

    private final StatementGenerator generator = new StatementGenerator();

    @Test
    void sameSeedAndClockGiveIdenticalBytes() throws IOException {
        for (StatementWriter writer : writers()) {
            byte[] first = write(writer, generate(Scenarios.defaultSixMonths(), SEED), "first");
            byte[] second = write(writer, generate(Scenarios.defaultSixMonths(), SEED), "second");

            assertThat(second).as(writer.fileExtension()).isEqualTo(first);
        }
    }

    @Test
    void differentSeedGivesDifferentBytes() throws IOException {
        for (StatementWriter writer : writers()) {
            byte[] first = write(writer, generate(Scenarios.defaultSixMonths(), SEED), "first");
            byte[] other = write(writer, generate(Scenarios.defaultSixMonths(), SEED + 1), "other");

            assertThat(other).as(writer.fileExtension()).isNotEqualTo(first);
        }
    }

    @Test
    void bytesDoNotDependOnDefaultLocaleOrTimeZone() throws IOException {
        GeneratedStatement statement = generate(Scenarios.withMalformedRows(), SEED);
        List<byte[]> reference = new ArrayList<>();
        for (StatementWriter writer : writers()) {
            reference.add(write(writer, statement, "reference"));
        }
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.of("hy", "AM"));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yerevan"));
            List<StatementWriter> writers = writers();
            for (int i = 0; i < writers.size(); i++) {
                assertThat(write(writers.get(i), statement, "armenian")).isEqualTo(reference.get(i));
            }
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
    }

    @Test
    void bothWritersProduceTheSameRows() throws IOException {
        GeneratedStatement statement = generate(Scenarios.withMalformedRows(), SEED);
        List<List<String>> expected = statement.lines().stream().map(StatementLayout::cells).toList();

        assertThat(readCsv(write(new CsvStatementWriter(), statement, "rows"))).containsExactlyElementsOf(expected);
        assertThat(readXlsx(write(new XlsxStatementWriter(CLOCK), statement, "rows")))
                .containsExactlyElementsOf(expected);
    }

    @Test
    void headersAreBilingualAndCsvStartsWithByteOrderMark() throws IOException {
        byte[] csv = write(new CsvStatementWriter(), generate(Scenarios.goldenSample(), SEED), "csv");
        String text = new String(csv, StandardCharsets.UTF_8);

        assertThat(text).startsWith("\uFEFFԱմսաթիվ / Date,Նկարագրություն / Description,Ելք / Debit,");
        assertThat(text).contains("\r\n").doesNotContain("\n\n");
    }

    @Test
    void xlsxUsesDateAndNumericCellsForValidRowsAndTextForMalformedOnes() throws IOException {
        GeneratedStatement statement = generate(Scenarios.withMalformedRows(), SEED);
        byte[] xlsx = write(new XlsxStatementWriter(CLOCK), statement, "types");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<StatementLine> lines = statement.lines();
            for (int i = 0; i < lines.size(); i++) {
                Cell date = sheet.getRow(i + HEADER_ROWS).getCell(0);
                if (lines.get(i) instanceof MalformedRow) {
                    assertThat(date.getCellType()).isEqualTo(CellType.STRING);
                } else {
                    assertThat(date.getCellType()).isEqualTo(CellType.NUMERIC);
                    assertThat(DateUtil.isCellDateFormatted(date)).isTrue();
                }
            }
        }
    }

    @Test
    void malformedRowsSitAtTheirListPositionPlusTheHeader() throws IOException {
        GeneratedStatement statement = generate(Scenarios.withMalformedRows(), SEED);
        byte[] csv = write(new CsvStatementWriter(), statement, "lines");

        try (CSVParser parser = CSVParser.parse(new String(csv, StandardCharsets.UTF_8).substring(1),
                CSVFormat.DEFAULT)) {
            List<CSVRecord> records = parser.getRecords();
            for (StatementLine line : statement.lines()) {
                if (line instanceof MalformedRow malformed) {
                    int lineNumber = statement.lines().indexOf(line) + HEADER_ROWS + 1;
                    assertThat(records.get(lineNumber - 1).get(2)).isEqualTo(malformed.debit());
                    assertThat(records.get(lineNumber - 1).getRecordNumber()).isEqualTo(lineNumber);
                }
            }
        }
    }

    private List<StatementWriter> writers() {
        return List.of(new CsvStatementWriter(), new XlsxStatementWriter(CLOCK));
    }

    private GeneratedStatement generate(Scenario scenario, long seed) {
        return generator.generate(scenario, RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
    }

    private byte[] write(StatementWriter writer, GeneratedStatement statement, String name) throws IOException {
        Path file = dir.resolve(name + "." + writer.fileExtension());
        try (OutputStream out = Files.newOutputStream(file)) {
            writer.write(statement, out);
        }
        return Files.readAllBytes(file);
    }

    private static List<List<String>> readCsv(byte[] csv) throws IOException {
        try (Reader reader = new StringReader(new String(csv, StandardCharsets.UTF_8).substring(1));
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                     .parse(reader)) {
            return parser.getRecords().stream().map(CSVRecord::toList).toList();
        }
    }

    private static List<List<String>> readXlsx(byte[] xlsx) throws IOException {
        try (InputStream in = new ByteArrayInputStream(xlsx); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (int r = HEADER_ROWS; r <= sheet.getLastRowNum(); r++) {
                rows.add(cellTexts(sheet.getRow(r)));
            }
            return rows;
        }
    }

    private static List<String> cellTexts(Row row) {
        List<String> texts = new ArrayList<>();
        for (int column = 0; column < StatementLayout.HEADERS.size(); column++) {
            texts.add(text(row.getCell(column)));
        }
        return texts;
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (DateUtil.isCellDateFormatted(cell)) {
            return GeneratedStatement.DATE_FORMAT.format(cell.getLocalDateTimeCellValue().toLocalDate());
        }
        return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
