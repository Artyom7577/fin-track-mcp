package com.artyomh.finance.adapter.parser.format;

import java.util.List;
import java.util.Objects;

/**
 * The header line, the non-blank lines after it, and the other sheets that hold data and were not read.
 */
public record SourceSheet(SourceLine header, List<SourceLine> lines, List<UnreadSheet> unreadSheets) {

    public SourceSheet {
        Objects.requireNonNull(header, "header");
        lines = List.copyOf(lines);
        unreadSheets = List.copyOf(unreadSheets);
    }
}
