package com.artyomh.finance.adapter.parser.format;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Reads one file format into lines of cells from the header on; empty when no header is found in the search window.
 */
public interface FormatReader {

    int HEADER_SEARCH_LINES = 20;

    Optional<SourceSheet> read(Path file, Predicate<List<String>> isHeader);
}
