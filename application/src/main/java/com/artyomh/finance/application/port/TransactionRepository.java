package com.artyomh.finance.application.port;

import com.artyomh.finance.domain.transaction.Transaction;
import com.artyomh.finance.domain.transaction.TransactionId;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface TransactionRepository {

    /**
     * Inserts in one transaction, skipping ids already stored, and returns the ids actually inserted.
     */
    Set<TransactionId> saveNew(Collection<Transaction> transactions);

    Collection<Transaction> findByMonth(YearMonth month);

    /**
     * Both dates inclusive.
     */
    Collection<Transaction> findBetween(LocalDate from, LocalDate to);

    /**
     * Months that have at least one transaction, oldest first.
     */
    List<YearMonth> findMonths();
}
