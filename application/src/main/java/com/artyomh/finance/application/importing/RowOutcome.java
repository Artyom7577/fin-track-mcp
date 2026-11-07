package com.artyomh.finance.application.importing;

import com.artyomh.finance.domain.transaction.Transaction;
import com.artyomh.finance.domain.transaction.TransactionId;

public sealed interface RowOutcome {

    record Imported(Transaction transaction) implements RowOutcome {
    }

    record Duplicate(TransactionId id) implements RowOutcome {
    }

    record Rejected(RowProblem problem) implements RowOutcome {
    }
}
