package com.artyomh.finance.adapter.parser.format;

import java.util.Objects;

/**
 * A sheet after the first that holds data; {@code position} is 1-based as Excel shows it, the name is raw file text.
 */
public record UnreadSheet(int position, String name, int rowCount) {

    public UnreadSheet {
        Objects.requireNonNull(name, "name");
    }
}
