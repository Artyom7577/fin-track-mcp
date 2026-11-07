package com.artyomh.finance.adapter.parser.format;

import com.artyomh.finance.adapter.parser.exception.ReadFailure;
import com.artyomh.finance.adapter.parser.exception.StatementReadException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the first sheet of an .xlsx read-only with typed cells; formulas give their cached value, never evaluated.
 */
public final class XlsxFormatReader implements FormatReader {

    private static final int BUFFER_BYTES = 64 * 1024;
    private static final BigDecimal BYTES_PER_MEGABYTE = BigDecimal.valueOf(1_000_000);
    // POI's memory follows the number of XML elements, not bytes: 50,000 realistic rows hold 550,148 elements
    // (about 11 per row), so this allows about twice that before POI builds anything
    private static final int MAX_ELEMENTS_PER_DATA_ROW = 22;

    private final int maxDataRows;
    private final long maxUnpackedBytes;
    private final long maxElements;

    public XlsxFormatReader(int maxDataRows, long maxUnpackedBytes) {
        this.maxDataRows = maxDataRows;
        this.maxUnpackedBytes = maxUnpackedBytes;
        this.maxElements = (long) maxDataRows * MAX_ELEMENTS_PER_DATA_ROW;
    }

    @Override
    public Optional<SourceSheet> read(Path file, Predicate<List<String>> isHeader) {
        checkUnpackedSize(file);
        OPCPackage opcPackage = open(file);
        try {
            return read(load(opcPackage, file), isHeader);
        } catch (StatementReadException e) {
            throw e;
        } catch (RuntimeException e) {
            // a damaged part can also fail while cells are read; POI's message never leaves this class
            throw damaged(file);
        } finally {
            // close() would try to save a read-only package and log a warning; revert just releases the file
            opcPackage.revert();
        }
    }

    private Optional<SourceSheet> read(XSSFWorkbook workbook, Predicate<List<String>> isHeader) {
        if (workbook.getNumberOfSheets() == 0) {
            return Optional.empty();
        }
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Sheet sheet = workbook.getSheetAt(0);
        Optional<SourceLine> header = findHeader(sheet, isHeader, formatter);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        // data lines get exactly the header's width, so cells right of the header are never read
        int width = header.get().contentWidth();
        DataLines data = new DataLines(maxDataRows);
        // the header's 1-based line number is the 0-based index of the row after it
        int firstDataRow = header.get().lineNumber();
        for (int index = firstDataRow; index <= sheet.getLastRowNum(); index++) {
            data.add(line(sheet.getRow(index), index, width, formatter));
        }
        return Optional.of(new SourceSheet(header.get(), data.lines(), unreadSheetsWithData(workbook, formatter)));
    }

    private static Optional<SourceLine> findHeader(Sheet sheet, Predicate<List<String>> isHeader,
                                                   DataFormatter formatter) {
        int last = Math.min(sheet.getLastRowNum(), HEADER_SEARCH_LINES - 1);
        for (int index = 0; index <= last; index++) {
            Row row = sheet.getRow(index);
            SourceLine line = line(row, index, lastCellCount(row), formatter);
            if (isHeader.test(line.texts())) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static int lastCellCount(Row row) {
        if (row == null) {
            return 0;
        }
        return Math.max(row.getLastCellNum(), 0);
    }

    private static SourceLine line(Row row, int index, int width, DataFormatter formatter) {
        List<Cell> cells = new ArrayList<>(width);
        for (int column = 0; column < width; column++) {
            if (row == null) {
                cells.add(Cell.BLANK);
            } else {
                cells.add(cell(row.getCell(column), formatter));
            }
        }
        return new SourceLine(index + 1, cells);
    }

    private static Cell cell(org.apache.poi.ss.usermodel.Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return Cell.BLANK;
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return value(cell, cell.getCachedFormulaResultType(), formatter);
        }
        return value(cell, cell.getCellType(), formatter);
    }

    private static Cell value(org.apache.poi.ss.usermodel.Cell cell, CellType type, DataFormatter formatter) {
        return switch (type) {
            case STRING -> Cell.ofText(cell.getStringCellValue());
            case NUMERIC -> numeric(cell, formatter);
            case BOOLEAN, ERROR -> Cell.INVALID;
            case BLANK, FORMULA, _NONE -> Cell.BLANK;
        };
    }

    private static Cell numeric(org.apache.poi.ss.usermodel.Cell cell, DataFormatter formatter) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return new Cell.DateCell(cell.getLocalDateTimeCellValue().toLocalDate());
        }
        double value = cell.getNumericCellValue();
        CellStyle style = cell.getCellStyle();
        return new Cell.Numeric(value,
                formatter.formatRawCellContents(value, style.getDataFormat(), style.getDataFormatString()));
    }

    private static List<UnreadSheet> unreadSheetsWithData(XSSFWorkbook workbook, DataFormatter formatter) {
        List<UnreadSheet> sheets = new ArrayList<>();
        for (int index = 1; index < workbook.getNumberOfSheets(); index++) {
            Sheet sheet = workbook.getSheetAt(index);
            int rows = rowsWithData(sheet, formatter);
            if (rows > 0) {
                sheets.add(new UnreadSheet(index + 1, sheet.getSheetName(), rows));
            }
        }
        return sheets;
    }

    private static int rowsWithData(Sheet sheet, DataFormatter formatter) {
        int rows = 0;
        for (Row row : sheet) {
            if (!line(row, row.getRowNum(), lastCellCount(row), formatter).isBlank()) {
                rows++;
            }
        }
        return rows;
    }

    // every part counts, since POI parses styles, comments and others on open and any can carry the bulk; counting
    // inflated bytes defeats a forged zip directory and keeps POI's JVM-wide ZipSecureFile settings at their defaults
    private void checkUnpackedSize(Path file) {
        UnpackedTally tally = new UnpackedTally();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    tally.add(in, maxUnpackedBytes, maxElements);
                }
                if (tally.bytes > maxUnpackedBytes) {
                    throw new StatementReadException(ReadFailure.FILE_TOO_LARGE, "The contents of "
                            + file.getFileName() + " unpack to more than " + megabytes(maxUnpackedBytes)
                            + " MB, the limit. Split the export by period.");
                }
                if (tally.elements > maxElements) {
                    throw new StatementReadException(ReadFailure.TOO_MANY_ROWS, String.format(Locale.ROOT,
                            "%s holds more cells than %,d statement rows; split the export by period and import "
                                    + "each part.", file.getFileName(), maxDataRows));
                }
            }
        } catch (StatementReadException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw damaged(file);
        }
    }

    /**
     * Inflated bytes and XML start tags across a workbook's parts, read at most one buffer past either budget.
     */
    private static final class UnpackedTally {

        private long bytes;
        private long elements;

        void add(InputStream in, long maxBytes, long maxElements) throws IOException {
            byte[] buffer = new byte[BUFFER_BYTES];
            boolean afterOpenBracket = false;
            int read = in.read(buffer);
            while (read >= 0 && bytes <= maxBytes && elements <= maxElements) {
                for (int i = 0; i < read; i++) {
                    // "<" then a name starts an element; "</", "<?" and "<!" do not
                    byte current = buffer[i];
                    if (afterOpenBracket && current != '/' && current != '?' && current != '!') {
                        elements++;
                    }
                    afterOpenBracket = current == '<';
                }
                bytes += read;
                read = in.read(buffer);
            }
        }
    }

    private static String megabytes(long bytes) {
        return BigDecimal.valueOf(bytes).divide(BYTES_PER_MEGABYTE, 1, RoundingMode.DOWN).toPlainString();
    }

    private static OPCPackage open(Path file) {
        try {
            return OPCPackage.open(file.toFile(), PackageAccess.READ);
        } catch (InvalidFormatException | RuntimeException e) {
            throw damaged(file);
        }
    }

    // POI's zip-bomb guard, XML errors and damaged parts all surface here; their messages never leave this class
    private static XSSFWorkbook load(OPCPackage pkg, Path file) {
        try {
            return new XSSFWorkbook(pkg);
        } catch (IOException | RuntimeException e) {
            throw damaged(file);
        }
    }

    private static StatementReadException damaged(Path file) {
        return new StatementReadException(ReadFailure.FILE_DAMAGED,
                file.getFileName() + " is damaged or not a real Excel file. Open it in Excel, save as .xlsx and "
                        + "import again.");
    }
}
