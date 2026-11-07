package com.artyomh.finance.adapter.parser.format;

import com.artyomh.finance.adapter.parser.exception.ReadFailure;
import com.artyomh.finance.adapter.parser.exception.StatementReadException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collects the non-blank lines after the header and stops the read as soon as they exceed the row limit.
 */
final class DataLines {

    private final int maxDataRows;
    private final List<SourceLine> lines = new ArrayList<>();

    DataLines(int maxDataRows) {
        this.maxDataRows = maxDataRows;
    }

    void add(SourceLine line) {
        if (line.isBlank()) {
            return;
        }
        if (lines.size() == maxDataRows) {
            throw new StatementReadException(ReadFailure.TOO_MANY_ROWS, String.format(Locale.ROOT,
                    "More than %,d rows; split the export by period and import each part.", maxDataRows));
        }
        lines.add(line);
    }

    List<SourceLine> lines() {
        return lines;
    }
}
