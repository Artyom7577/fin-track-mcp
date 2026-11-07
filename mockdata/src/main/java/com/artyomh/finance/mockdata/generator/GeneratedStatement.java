package com.artyomh.finance.mockdata.generator;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Statement lines in file order: by date, then by generation sequence within a day.
 */
public record GeneratedStatement(List<StatementLine> lines) {

    /**
     * The statement's date text, shared by malformed rows and the writers.
     */
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

    public GeneratedStatement {
        lines = List.copyOf(lines);
    }

    public List<GeneratedRow> rows() {
        return lines.stream().filter(GeneratedRow.class::isInstance).map(GeneratedRow.class::cast).toList();
    }
}
