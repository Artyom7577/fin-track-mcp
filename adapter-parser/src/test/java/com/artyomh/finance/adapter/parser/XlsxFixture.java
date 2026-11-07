package com.artyomh.finance.adapter.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbookPr;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds hand-shaped .xlsx files with typed cells for cases the mockdata writer never produces.
 */
public final class XlsxFixture {

    /**
     * A formula cell with a cached result; the parser must read the cache and never evaluate.
     */
    public record Formula(String expression, Object cached) {
    }

    public record Error(FormulaError error) {
    }

    /**
     * A plain number cell without date formatting, even though it may hold a date serial.
     */
    public record Plain(double value) {
    }

    /**
     * A date-formatted cell holding a raw serial, read in whichever date system the workbook uses.
     */
    public record DateSerial(double serial) {
    }

    private final Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
    private final Map<String, List<String>> merged = new LinkedHashMap<>();
    private boolean date1904;

    public XlsxFixture date1904() {
        date1904 = true;
        return this;
    }

    public XlsxFixture merge(String sheet, String range) {
        merged.computeIfAbsent(sheet, name -> new ArrayList<>()).add(range);
        return this;
    }

    public XlsxFixture sheet(String name, List<List<Object>> rows) {
        sheets.put(name, rows);
        return this;
    }

    public Path writeTo(Path file) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd.mm.yyyy"));
            CellStyle amountStyle = workbook.createCellStyle();
            amountStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            CTWorkbook ctWorkbook = workbook.getCTWorkbook();
            CTWorkbookPr properties =
                    ctWorkbook.isSetWorkbookPr() ? ctWorkbook.getWorkbookPr() : ctWorkbook.addNewWorkbookPr();
            properties.setDate1904(date1904);
            sheets.forEach((name, rows) -> fill(workbook.createSheet(name), rows, dateStyle, amountStyle));
            merged.forEach((name, ranges) -> ranges.forEach(
                    range -> workbook.getSheet(name).addMergedRegion(CellRangeAddress.valueOf(range))));
            workbook.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    private static void fill(Sheet sheet, List<List<Object>> rows, CellStyle dateStyle, CellStyle amountStyle) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            List<Object> values = rows.get(r);
            for (int c = 0; c < values.size(); c++) {
                if (values.get(c) != null) {
                    set(row.createCell(c), values.get(c), dateStyle, amountStyle);
                }
            }
        }
    }

    private static void set(Cell cell, Object value, CellStyle dateStyle, CellStyle amountStyle) {
        switch (value) {
            case String text -> cell.setCellValue(text);
            case LocalDate date -> {
                cell.setCellValue(date);
                cell.setCellStyle(dateStyle);
            }
            case Number number -> {
                cell.setCellValue(number.doubleValue());
                cell.setCellStyle(amountStyle);
            }
            case Plain plain -> cell.setCellValue(plain.value());
            case DateSerial date -> {
                cell.setCellValue(date.serial());
                cell.setCellStyle(dateStyle);
            }
            case Boolean flag -> cell.setCellValue(flag);
            case Error error -> cell.setCellErrorValue(error.error().getCode());
            case Formula formula -> {
                cell.setCellFormula(formula.expression());
                set(cell, formula.cached(), dateStyle, amountStyle);
            }
            default -> throw new IllegalArgumentException("unsupported fixture value " + value);
        }
    }
}
