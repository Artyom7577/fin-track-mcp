package com.artyomh.finance.adapter.parser;

import com.artyomh.finance.adapter.parser.evoca.EvocaColumns;
import com.artyomh.finance.adapter.parser.evoca.EvocaRowMapper;
import com.artyomh.finance.adapter.parser.exception.ReadFailure;
import com.artyomh.finance.adapter.parser.exception.StatementReadException;
import com.artyomh.finance.adapter.parser.format.CsvFormatReader;
import com.artyomh.finance.adapter.parser.format.FormatReader;
import com.artyomh.finance.adapter.parser.format.SourceSheet;
import com.artyomh.finance.adapter.parser.format.UnreadSheet;
import com.artyomh.finance.adapter.parser.format.XlsxFormatReader;
import com.artyomh.finance.application.importing.FileWarning;
import com.artyomh.finance.application.importing.ParseResult;
import com.artyomh.finance.application.port.StatementParser;
import com.artyomh.finance.domain.merchant.CardNumberMasker;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class StatementFileParser implements StatementParser {

    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] OLE2_SIGNATURE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};
    private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46};
    private static final byte[] UTF8_BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final List<String> WEB_PAGE_STARTS = List.of("<html", "<!doctype");
    private static final int SNIFFED_BYTES = 512;
    private static final BigDecimal BYTES_PER_MEGABYTE = BigDecimal.valueOf(1_000_000);
    private static final String SUPPORTED = "Supported: Evoca .xlsx or .csv export.";
    private static final Pattern INVISIBLE_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final int MAX_SHEET_NAME_CODE_POINTS = 40;

    private enum FileKind {
        XLSX(".xlsx"),
        CSV(".csv");

        private final String extension;

        FileKind(String extension) {
            this.extension = extension;
        }
    }

    private final ParserLimits limits;
    private final FormatReader xlsxReader;
    private final FormatReader csvReader;

    public StatementFileParser(ParserLimits limits) {
        this.limits = limits;
        this.xlsxReader = new XlsxFormatReader(limits.maxDataRows(), limits.maxXlsxUnpackedBytes());
        this.csvReader = new CsvFormatReader(limits.maxDataRows());
    }

    @Override
    public ParseResult parse(Path file) {
        long size = readableSize(file);
        if (size == 0) {
            throw new StatementReadException(ReadFailure.FILE_EMPTY,
                    file.getFileName() + " is empty. Export the statement again.");
        }
        FileKind kind = kindOf(firstBytes(file));
        checkSize(kind, size);
        checkExtension(file, kind);
        SourceSheet sheet = reader(kind).read(file, EvocaColumns::isHeader)
                .orElseThrow(() -> new StatementReadException(ReadFailure.HEADER_NOT_FOUND,
                        "No statement header found in the first " + FormatReader.HEADER_SEARCH_LINES + " rows of "
                                + file.getFileName() + ". Expected columns: " + EvocaColumns.expected() + "."));
        ParseResult mapped = new EvocaRowMapper(sheet.header()).map(sheet.lines());
        return withUnreadSheets(mapped, sheet.unreadSheets());
    }

    private static long readableSize(Path file) {
        try {
            if (Files.isRegularFile(file) && Files.isReadable(file)) {
                return Files.size(file);
            }
        } catch (IOException e) {
            // reported below like any other unreadable file
        }
        throw notReadable(file);
    }

    private void checkSize(FileKind kind, long size) {
        long limit = switch (kind) {
            case XLSX -> limits.maxXlsxBytes();
            case CSV -> limits.maxCsvBytes();
        };
        if (size > limit) {
            throw new StatementReadException(ReadFailure.FILE_TOO_LARGE, "File is "
                    + megabytes(size, RoundingMode.UP) + " MB; the limit for " + kind.extension + " is "
                    + megabytes(limit, RoundingMode.DOWN) + " MB. Split the export by period.");
        }
    }

    // MB is 1,000,000 bytes; BigDecimal keeps the text locale-free
    private static String megabytes(long bytes, RoundingMode rounding) {
        return BigDecimal.valueOf(bytes).divide(BYTES_PER_MEGABYTE, 1, rounding).toPlainString();
    }

    private static byte[] firstBytes(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(SNIFFED_BYTES);
        } catch (IOException e) {
            throw notReadable(file);
        }
    }

    private static FileKind kindOf(byte[] head) {
        if (startsWith(head, OLE2_SIGNATURE)) {
            throw new StatementReadException(ReadFailure.LEGACY_XLS,
                    "Old .xls format is not supported. Open it in Excel and save as .xlsx.");
        }
        if (startsWith(head, PDF_SIGNATURE)) {
            throw new StatementReadException(ReadFailure.PDF_NOT_SUPPORTED,
                    "PDF statements are not supported. Export as .xlsx or .csv.");
        }
        if (isWebPage(head)) {
            throw new StatementReadException(ReadFailure.WEB_PAGE,
                    "This file is a web page, not a spreadsheet. Export as .xlsx or .csv.");
        }
        if (startsWith(head, ZIP_SIGNATURE)) {
            return FileKind.XLSX;
        }
        return FileKind.CSV;
    }

    private static boolean isWebPage(byte[] head) {
        int start = 0;
        if (startsWith(head, UTF8_BYTE_ORDER_MARK)) {
            start = UTF8_BYTE_ORDER_MARK.length;
        }
        String text = new String(head, start, head.length - start, StandardCharsets.ISO_8859_1).stripLeading()
                .toLowerCase(Locale.ROOT);
        return WEB_PAGE_STARTS.stream().anyMatch(text::startsWith);
    }

    private static boolean startsWith(byte[] head, byte[] prefix) {
        return head.length >= prefix.length && Arrays.equals(head, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static void checkExtension(Path file, FileKind kind) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(kind.extension)) {
            return;
        }
        String message = switch (kind) {
            case XLSX -> file.getFileName() + " is an Excel workbook but is not named .xlsx. " + SUPPORTED;
            case CSV -> file.getFileName() + " is not a valid .xlsx or .csv file. " + SUPPORTED;
        };
        throw new StatementReadException(ReadFailure.EXTENSION_MISMATCH, message);
    }

    private FormatReader reader(FileKind kind) {
        return switch (kind) {
            case XLSX -> xlsxReader;
            case CSV -> csvReader;
        };
    }

    private static ParseResult withUnreadSheets(ParseResult mapped, Collection<UnreadSheet> unreadSheets) {
        if (unreadSheets.isEmpty()) {
            return mapped;
        }
        List<FileWarning> warnings = new ArrayList<>(mapped.warnings());
        for (UnreadSheet sheet : unreadSheets) {
            warnings.add(new FileWarning(FileWarning.Code.SHEET_NOT_READ,
                    "sheet " + sheet.position() + " ('" + cleanName(sheet.name()) + "') was not imported",
                    sheet.rowCount()));
        }
        return new ParseResult(mapped.rows(), mapped.problems(), warnings);
    }

    // a sheet name is file text that reaches Claude: invisible characters go, card numbers are masked, length capped
    private static String cleanName(String name) {
        String masked = CardNumberMasker.mask(INVISIBLE_CHARACTERS.matcher(name).replaceAll(""));
        if (masked.codePointCount(0, masked.length()) <= MAX_SHEET_NAME_CODE_POINTS) {
            return masked;
        }
        return masked.substring(0, masked.offsetByCodePoints(0, MAX_SHEET_NAME_CODE_POINTS));
    }

    private static StatementReadException notReadable(Path file) {
        return new StatementReadException(ReadFailure.FILE_NOT_READABLE,
                "Cannot read " + file.getFileName() + ". Check that the file exists and is readable.");
    }
}
