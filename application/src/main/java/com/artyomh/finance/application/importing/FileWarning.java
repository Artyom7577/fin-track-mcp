package com.artyomh.finance.application.importing;

import java.util.Objects;

/**
 * Data the file holds but the import skipped as a whole, such as a second sheet; not counted as rejected rows.
 */
public record FileWarning(Code code, String message, int rowCount) {

    public enum Code {
        ROWS_AFTER_TOTALS,
        SHEET_NOT_READ
    }

    public FileWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (rowCount <= 0) {
            throw new IllegalArgumentException("a warning covers at least one row: " + rowCount);
        }
    }
}
