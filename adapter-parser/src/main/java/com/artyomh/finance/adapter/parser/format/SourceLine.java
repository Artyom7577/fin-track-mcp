package com.artyomh.finance.adapter.parser.format;

import java.util.List;

/**
 * The cells of one file line, numbered as the file shows it (1-based).
 */
public record SourceLine(int lineNumber, List<Cell> cells) {

    public SourceLine {
        cells = List.copyOf(cells);
    }

    /**
     * The cell at a column, blank past the end of the line.
     */
    public Cell cell(int column) {
        if (column < cells.size()) {
            return cells.get(column);
        }
        return Cell.BLANK;
    }

    /**
     * The number of cells up to the last non-blank one.
     */
    public int contentWidth() {
        int width = cells.size();
        while (width > 0 && cells.get(width - 1) instanceof Cell.Blank) {
            width--;
        }
        return width;
    }

    public List<String> texts() {
        return cells.stream().map(Cell::text).toList();
    }

    boolean isBlank() {
        return cells.stream().allMatch(Cell.Blank.class::isInstance);
    }
}
