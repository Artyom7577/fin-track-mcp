package com.artyomh.finance.mockdata.writer;

import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.mockdata.generator.GeneratedRow;
import com.artyomh.finance.mockdata.generator.GeneratedStatement;
import com.artyomh.finance.mockdata.generator.MalformedRow;
import com.artyomh.finance.mockdata.generator.StatementLine;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Real date and numeric cells like a bank export, malformed values as text; all timestamps come from the clock.
 */
public final class XlsxStatementWriter implements StatementWriter {

    private static final String SHEET_NAME = "Statement";
    private static final String DATE_PATTERN = "dd.mm.yyyy";
    private static final String AMOUNT_PATTERN = "#,##0.00";
    private static final int DATE_COLUMN = 0;
    private static final int DESCRIPTION_COLUMN = 1;
    private static final int DEBIT_COLUMN = 2;
    private static final int CREDIT_COLUMN = 3;
    private static final int CURRENCY_COLUMN = 4;
    private static final int BALANCE_COLUMN = 5;

    private final Clock clock;

    public XlsxStatementWriter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void write(GeneratedStatement statement, OutputStream out) throws IOException {
        ByteArrayOutputStream workbookBytes = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            stampProperties(workbook.getProperties().getCoreProperties());
            fill(workbook, statement);
            workbook.write(workbookBytes);
        }
        repackWithFixedTimes(workbookBytes.toByteArray(), out);
    }

    @Override
    public String fileExtension() {
        return "xlsx";
    }

    private void stampProperties(POIXMLProperties.CoreProperties properties) {
        Optional<Date> now = Optional.of(Date.from(clock.instant()));
        properties.setCreated(now);
        properties.setModified(now);
    }

    private static void fill(XSSFWorkbook workbook, GeneratedStatement statement) {
        DataFormat formats = workbook.createDataFormat();
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(formats.getFormat(DATE_PATTERN));
        CellStyle amountStyle = workbook.createCellStyle();
        amountStyle.setDataFormat(formats.getFormat(AMOUNT_PATTERN));

        Sheet sheet = workbook.createSheet(SHEET_NAME);
        writeText(sheet.createRow(0), StatementLayout.HEADERS);
        List<StatementLine> lines = statement.lines();
        for (int i = 0; i < lines.size(); i++) {
            Row row = sheet.createRow(i + 1);
            switch (lines.get(i)) {
                case GeneratedRow generated -> writeTyped(row, generated, dateStyle, amountStyle);
                case MalformedRow malformed -> writeText(row, StatementLayout.cells(malformed));
            }
        }
    }

    private static void writeTyped(Row row, GeneratedRow generated, CellStyle dateStyle, CellStyle amountStyle) {
        row.createCell(DATE_COLUMN).setCellValue(generated.date());
        row.getCell(DATE_COLUMN).setCellStyle(dateStyle);
        row.createCell(DESCRIPTION_COLUMN).setCellValue(generated.description());
        int amountColumn = switch (generated.direction()) {
            case EXPENSE -> DEBIT_COLUMN;
            case INCOME -> CREDIT_COLUMN;
        };
        writeAmount(row, amountColumn, generated.money().amount(), amountStyle);
        row.createCell(CURRENCY_COLUMN).setCellValue(generated.money().currency().name());
        writeAmount(row, BALANCE_COLUMN, generated.balanceAfter(), amountStyle);
    }

    private static void writeAmount(Row row, int column, BigDecimal amount, CellStyle amountStyle) {
        // Excel numeric cells are doubles; two-decimal amounts this size read back exactly with the currency's scale
        row.createCell(column).setCellValue(amount.doubleValue());
        row.getCell(column).setCellStyle(amountStyle);
    }

    private static void writeText(Row row, List<String> cells) {
        for (int column = 0; column < cells.size(); column++) {
            if (!cells.get(column).isEmpty()) {
                row.createCell(column).setCellValue(cells.get(column));
            }
        }
    }

    // POI stamps zip entries with the wall clock; rewrite them so equal input gives equal bytes
    private void repackWithFixedTimes(byte[] workbook, OutputStream out) throws IOException {
        LocalDateTime entryTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ZipOutputStream zip = new ZipOutputStream(out);
        try (ZipInputStream source = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (ZipEntry entry = source.getNextEntry(); entry != null; entry = source.getNextEntry()) {
                byte[] content = source.readAllBytes();
                zip.putNextEntry(copyOf(entry, content, entryTime));
                zip.write(content);
                zip.closeEntry();
            }
        }
        // finish, not close: the caller owns the stream
        zip.finish();
    }

    private static ZipEntry copyOf(ZipEntry entry, byte[] content, LocalDateTime entryTime) {
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setMethod(entry.getMethod());
        copy.setTimeLocal(entryTime);
        if (entry.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(content);
            copy.setSize(content.length);
            copy.setCompressedSize(content.length);
            copy.setCrc(crc.getValue());
        }
        return copy;
    }
}
