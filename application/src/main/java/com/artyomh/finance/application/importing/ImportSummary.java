package com.artyomh.finance.application.importing;

import java.util.Collection;
import java.util.List;

/**
 * Every data row is added, a duplicate or rejected; warnings describe data the file holds but was not read.
 */
public record ImportSummary(String file, int rowsRead, int added, int duplicates, Collection<RowProblem> rejected,
                            Collection<FileWarning> warnings) {

    public ImportSummary {
        rejected = List.copyOf(rejected);
        warnings = List.copyOf(warnings);
    }
}
