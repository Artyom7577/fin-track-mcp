package com.artyomh.finance.mockdata.writer;

import com.artyomh.finance.mockdata.generator.GeneratedStatement;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Formats a generated statement as one file type; writers never compute amounts or balances.
 */
public interface StatementWriter {

    void write(GeneratedStatement statement, OutputStream out) throws IOException;

    String fileExtension();
}
