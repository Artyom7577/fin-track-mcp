package com.artyomh.finance.application.exception;

import com.artyomh.finance.domain.exception.FinanceException;

import java.time.YearMonth;
import java.util.List;
import java.util.SequencedCollection;

public final class MonthNotFoundException extends FinanceException {

    private static final String CODE = "MONTH_NOT_FOUND";

    private final YearMonth requested;
    private final List<YearMonth> available;

    public MonthNotFoundException(YearMonth requested, SequencedCollection<YearMonth> available) {
        super(messageFor(requested, available));
        this.requested = requested;
        this.available = List.copyOf(available);
    }

    public YearMonth requested() {
        return requested;
    }

    public List<YearMonth> available() {
        return available;
    }

    @Override
    public String code() {
        return CODE;
    }

    private static String messageFor(YearMonth requested, SequencedCollection<YearMonth> available) {
        if (available.isEmpty()) {
            return "No statements imported yet; import a statement first.";
        }
        if (available.size() == 1) {
            return "No data for %s. Available: %s".formatted(requested, available.getFirst());
        }
        return "No data for %s. Available: %s to %s".formatted(requested, available.getFirst(), available.getLast());
    }
}
