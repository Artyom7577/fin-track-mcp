package com.artyomh.finance.adapter.parser.format;

import com.artyomh.finance.adapter.parser.exception.ReadFailure;
import com.artyomh.finance.adapter.parser.exception.StatementReadException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Strict UTF-8 CSV with an optional BOM; the delimiter is the first one under which the header is found.
 */
public final class CsvFormatReader implements FormatReader {

    private static final List<Character> DELIMITERS = List.of(',', ';', '\t');
    private static final String BYTE_ORDER_MARK = "﻿";

    private final int maxDataRows;

    public CsvFormatReader(int maxDataRows) {
        this.maxDataRows = maxDataRows;
    }

    @Override
    public Optional<SourceSheet> read(Path file, Predicate<List<String>> isHeader) {
        String text = readUtf8(file);
        for (char delimiter : DELIMITERS) {
            Optional<SourceSheet> sheet = read(text, delimiter, isHeader);
            if (sheet.isPresent()) {
                return sheet;
            }
        }
        return Optional.empty();
    }

    private Optional<SourceSheet> read(String text, char delimiter, Predicate<List<String>> isHeader) {
        // trailing data after a closing quote is kept, so the only structural error left is an unclosed quote
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(false)
                .setTrailingData(true)
                .get();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            Iterator<CSVRecord> records = parser.iterator();
            Optional<SourceLine> header = findHeader(parser, records, isHeader);
            if (header.isEmpty()) {
                return Optional.empty();
            }
            DataLines data = new DataLines(maxDataRows);
            for (Optional<SourceLine> line = next(parser, records); line.isPresent(); line = next(parser, records)) {
                data.add(line.get());
            }
            return Optional.of(new SourceSheet(header.get(), data.lines(), List.of()));
        } catch (IOException e) {
            // an in-memory reader cannot fail; this only satisfies the parser's signature
            throw new StatementReadException(ReadFailure.FILE_NOT_READABLE, "Cannot read the CSV text.");
        }
    }

    private static Optional<SourceLine> findHeader(CSVParser parser, Iterator<CSVRecord> records,
                                                   Predicate<List<String>> isHeader) {
        for (int i = 0; i < HEADER_SEARCH_LINES; i++) {
            Optional<SourceLine> line = next(parser, records);
            if (line.isEmpty() || isHeader.test(line.get().texts())) {
                return line;
            }
        }
        return Optional.empty();
    }

    private static Optional<SourceLine> next(CSVParser parser, Iterator<CSVRecord> records) {
        // the parser has consumed every line before the next record, so this is the line the record starts on
        int lineNumber = Math.toIntExact(parser.getCurrentLineNumber()) + 1;
        try {
            if (!records.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(new SourceLine(lineNumber, records.next().stream().map(Cell::ofText).toList()));
        } catch (UncheckedIOException e) {
            throw new StatementReadException(ReadFailure.MALFORMED_CSV,
                    "Unclosed quote starting at line " + lineNumber + ". Fix the file and import again.");
        }
    }

    private static String readUtf8(Path file) {
        try {
            String text = Files.readString(file);
            if (text.startsWith(BYTE_ORDER_MARK)) {
                return text.substring(BYTE_ORDER_MARK.length());
            }
            return text;
        } catch (CharacterCodingException e) {
            throw new StatementReadException(ReadFailure.NOT_UTF8,
                    file.getFileName() + " is not UTF-8 text. Save it as \"CSV UTF-8\" and import again.");
        } catch (IOException e) {
            throw new StatementReadException(ReadFailure.FILE_NOT_READABLE, "Cannot read " + file.getFileName() + ".");
        }
    }
}
