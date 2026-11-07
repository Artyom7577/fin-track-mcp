package com.artyomh.finance.application.fake;

import com.artyomh.finance.application.port.TransactionRepository;
import com.artyomh.finance.domain.transaction.Transaction;
import com.artyomh.finance.domain.transaction.TransactionId;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<TransactionId, Transaction> rows = new LinkedHashMap<>();

    @Override
    public Set<TransactionId> saveNew(Collection<Transaction> transactions) {
        Set<TransactionId> inserted = new LinkedHashSet<>();
        for (Transaction transaction : transactions) {
            if (rows.putIfAbsent(transaction.id(), transaction) == null) {
                inserted.add(transaction.id());
            }
        }
        return inserted;
    }

    @Override
    public Collection<Transaction> findByMonth(YearMonth month) {
        return rows.values().stream().filter(t -> t.month().equals(month)).toList();
    }

    @Override
    public Collection<Transaction> findBetween(LocalDate from, LocalDate to) {
        return rows.values().stream().filter(t -> !t.date().isBefore(from) && !t.date().isAfter(to)).toList();
    }

    @Override
    public List<YearMonth> findMonths() {
        return rows.values().stream().map(Transaction::month).distinct().sorted().toList();
    }
}
