package com.artyomh.finance.mockdata.writer;

import com.artyomh.finance.mockdata.generator.GeneratedStatement;
import com.artyomh.finance.mockdata.generator.StatementLine;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * UTF-8 with a BOM so Excel shows the Armenian headers; every cell is text.
 */
public final class CsvStatementWriter implements StatementWriter {

    private static final char BYTE_ORDER_MARK = '\uFEFF';
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").get();

    @Override
    public void write(GeneratedStatement statement, OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(BYTE_ORDER_MARK);
        CSVPrinter printer = new CSVPrinter(writer, FORMAT);
        printer.printRecord(StatementLayout.HEADERS);
        for (StatementLine line : statement.lines()) {
            printer.printRecord(StatementLayout.cells(line));
        }
        // flush, not close: the caller owns the stream
        printer.flush();
    }

    @Override
    public String fileExtension() {
        return "csv";
    }
}
