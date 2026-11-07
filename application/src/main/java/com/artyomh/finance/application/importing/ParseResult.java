package com.artyomh.finance.application.importing;

import java.util.Collection;
import java.util.List;
import java.util.SequencedCollection;

public record ParseResult(SequencedCollection<ParsedRow> rows, Collection<RowProblem> problems,
                          Collection<FileWarning> warnings) {

    public ParseResult {
        rows = List.copyOf(rows);
        problems = List.copyOf(problems);
        warnings = List.copyOf(warnings);
    }
}
