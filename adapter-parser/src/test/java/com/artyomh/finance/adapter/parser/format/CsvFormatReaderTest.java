package com.artyomh.finance.adapter.parser.format;

import com.artyomh.finance.adapter.parser.exception.ReadFailure;
import com.artyomh.finance.adapter.parser.exception.StatementReadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CsvFormatReaderTest {

    private static final Predicate<List<String>> HEADER = cells -> cells.contains("Date");

    @TempDir
    private Path dir;

    private final CsvFormatReader reader = new CsvFormatReader(100);

    @ParameterizedTest
    @ValueSource(strings = {",", ";", "\t"})
    void picksTheDelimiterUnderWhichTheHeaderIsFound(String delimiter) throws IOException {
        SourceSheet sheet = read(String.join(delimiter, "Date", "Description") + "\r\n"
                + String.join(delimiter, "01.05.2026", "SHOP") + "\r\n").orElseThrow();

        assertThat(sheet.header().texts()).containsExactly("Date", "Description");
        assertThat(sheet.lines()).singleElement().extracting(SourceLine::texts)
                .isEqualTo(List.of("01.05.2026", "SHOP"));
    }

    @Test
    void stripsTheByteOrderMarkSoTheFirstLabelMatches() throws IOException {
        assertThat(read("﻿Date,Description\n")).isPresent();
    }

    @Test
    void findsAHeaderBelowATitleBlockAndKeepsFileLineNumbers() throws IOException {
        SourceSheet sheet = read("Statement,\nAccount 4578****1234,\n\nDate,Description\n01.05.2026,A\n\n\n02.05.2026,B\n")
                .orElseThrow();

        assertThat(sheet.header().lineNumber()).isEqualTo(4);
        assertThat(sheet.lines()).extracting(SourceLine::lineNumber).containsExactly(5, 8);
    }

    @Test
    void aQuotedLineBreakDoesNotShiftLaterLineNumbers() throws IOException {
        SourceSheet sheet = read("Date,Description\r\n01.05.2026,\"TWO\r\nLINES\"\r\n02.05.2026,\"A, \"\"B\"\"\"\r\n")
                .orElseThrow();

        assertThat(sheet.lines()).extracting(SourceLine::lineNumber).containsExactly(2, 4);
        assertThat(sheet.lines().getLast().texts()).containsExactly("02.05.2026", "A, \"B\"");
    }

    @Test
    void blankAndWhitespaceOnlyCellsAreBlank() throws IOException {
        SourceSheet sheet = read("Date,Description\n01.05.2026,  \n , \n").orElseThrow();

        assertThat(sheet.lines()).singleElement().extracting(line -> line.cell(1)).isEqualTo(Cell.BLANK);
    }

    @Test
    void noHeaderInTheFirstTwentyLinesIsEmpty() throws IOException {
        assertThat(read("x\n".repeat(FormatReader.HEADER_SEARCH_LINES) + "Date,Description\n")).isEmpty();
        assertThat(read("x\n".repeat(FormatReader.HEADER_SEARCH_LINES - 1) + "Date,Description\n")).isPresent();
    }

    @Test
    void anUnclosedQuoteFailsTheWholeFileAtTheLineWhereItOpened() {
        assertThatExceptionOfType(StatementReadException.class)
                .isThrownBy(() -> read("Date,Description\n01.05.2026,A\n02.05.2026,\"B\n03.05.2026,C\n"))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.MALFORMED_CSV))
                .withMessage("Unclosed quote starting at line 3. Fix the file and import again.");
    }

    @Test
    void textAfterAClosingQuoteIsKept() throws IOException {
        SourceSheet sheet = read("Date,Description\n01.05.2026,\"A\"B\n").orElseThrow();

        assertThat(sheet.lines().getFirst().texts()).containsExactly("01.05.2026", "AB");
    }

    @Test
    void rejectsTextThatIsNotUtf8() throws IOException {
        Path file = dir.resolve("cp1251.csv");
        Files.write(file, "Date,Описание\n".getBytes("windows-1251"));

        assertThatExceptionOfType(StatementReadException.class).isThrownBy(() -> reader.read(file, HEADER))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.NOT_UTF8));
    }

    @Test
    void stopsAsSoonAsTheRowLimitIsExceeded() throws IOException {
        Path file = write("Date\n" + "01.05.2026\n\n".repeat(3));

        assertThat(new CsvFormatReader(3).read(file, HEADER).orElseThrow().lines()).hasSize(3);
        assertThatExceptionOfType(StatementReadException.class)
                .isThrownBy(() -> new CsvFormatReader(2).read(file, HEADER))
                .satisfies(e -> assertThat(e.failure()).isEqualTo(ReadFailure.TOO_MANY_ROWS))
                .withMessageContaining("More than 2 rows");
    }

    private Optional<SourceSheet> read(String text) throws IOException {
        return reader.read(write(text), HEADER);
    }

    private Path write(String text) throws IOException {
        return Files.writeString(dir.resolve("statement.csv"), text, StandardCharsets.UTF_8);
    }
}
