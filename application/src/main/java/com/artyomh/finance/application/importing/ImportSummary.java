package com.artyomh.finance.application.importing;

import java.util.Collection;
import java.util.List;

public record ImportSummary(String file, int rowsRead, int added, int duplicates, Collection<RowProblem> rejected) {

    public ImportSummary {
        rejected = List.copyOf(rejected);
    }
}
