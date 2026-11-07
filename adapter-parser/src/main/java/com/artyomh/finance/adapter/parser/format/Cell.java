package com.artyomh.finance.adapter.parser.format;

import java.time.LocalDate;

/**
 * One cell typed as the file stored it; CSV cells are only text or blank.
 */
public sealed interface Cell {

    Blank BLANK = new Blank();
    Invalid INVALID = new Invalid();

    /**
     * What a person reads in the cell, used to recognize header labels.
     */
    String text();

    /**
     * Text that is empty or only whitespace, no-break spaces included, is blank.
     */
    static Cell ofText(String value) {
        if (value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            return BLANK;
        }
        return new Text(value);
    }

    record Text(String value) implements Cell {

        @Override
        public String text() {
            return value;
        }
    }

    /**
     * A number cell that is not date-formatted, with its text formatted locale-free.
     */
    record Numeric(double value, String formatted) implements Cell {

        @Override
        public String text() {
            return formatted;
        }
    }

    record DateCell(LocalDate value) implements Cell {

        @Override
        public String text() {
            return value.toString();
        }
    }

    record Blank() implements Cell {

        @Override
        public String text() {
            return "";
        }
    }

    /**
     * A boolean or error value such as {@code #N/A}, which no statement column may hold.
     */
    record Invalid() implements Cell {

        @Override
        public String text() {
            return "";
        }
    }
}
