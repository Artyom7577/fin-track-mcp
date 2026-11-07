package com.artyomh.finance.application.analysis.spending;

import com.artyomh.finance.application.analysis.Percent;
import com.artyomh.finance.application.exception.InvalidInputException;
import com.artyomh.finance.application.exception.MonthNotFoundException;
import com.artyomh.finance.application.port.TransactionRepository;
import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.domain.transaction.Transaction;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SpendingService {

    private static final BigDecimal ZERO_AMD = BigDecimal.ZERO.setScale(Currency.AMD.minorDigits());
    private static final int MIN_LIMIT = 1;

    private final TransactionRepository transactions;
    private final int maxMerchantLimit;

    public SpendingService(TransactionRepository transactions, int maxMerchantLimit) {
        if (maxMerchantLimit < MIN_LIMIT) {
            throw new IllegalArgumentException("maxMerchantLimit must be positive: " + maxMerchantLimit);
        }
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.maxMerchantLimit = maxMerchantLimit;
    }

    public MonthlySpending spendingByCategory(YearMonth month) {
        Collection<Transaction> rows = rowsOf(month);
        Collection<Transaction> expenses = expensesOf(rows);
        BigDecimal spent = totalAmd(expenses);
        List<CategorySpending> categories = groupBy(expenses, Transaction::category).entrySet().stream()
                .map(entry -> categorySpending(entry.getKey(), entry.getValue(), spent))
                .sorted(Comparator.comparing(CategorySpending::amountAmd).reversed()
                        .thenComparing(CategorySpending::category))
                .toList();
        return new MonthlySpending(month, spent, totalAmd(incomeOf(rows)), categories);
    }

    public List<MerchantSpending> topMerchants(YearMonth month, int limit) {
        if (limit < MIN_LIMIT || limit > maxMerchantLimit) {
            throw new InvalidInputException(
                    "limit must be %d-%d, got %d".formatted(MIN_LIMIT, maxMerchantLimit, limit));
        }
        Collection<Transaction> expenses = expensesOf(rowsOf(month));
        BigDecimal spent = totalAmd(expenses);
        return groupBy(expenses, Transaction::merchant).entrySet().stream()
                .map(entry -> merchantSpending(entry.getKey(), entry.getValue(), spent))
                .sorted(Comparator.comparing(MerchantSpending::amountAmd).reversed()
                        .thenComparing(MerchantSpending::merchant))
                .limit(limit)
                .toList();
    }

    public List<MonthSummary> listMonths() {
        List<YearMonth> months = availableMonths();
        if (months.isEmpty()) {
            return List.of();
        }
        Map<YearMonth, List<Transaction>> byMonth = transactions
                .findBetween(months.getFirst().atDay(1), months.getLast().atEndOfMonth()).stream()
                .collect(Collectors.groupingBy(Transaction::month, TreeMap::new, Collectors.toList()));
        return byMonth.entrySet().stream()
                .map(entry -> new MonthSummary(entry.getKey(), totalAmd(incomeOf(entry.getValue())),
                        totalAmd(expensesOf(entry.getValue())), entry.getValue().size()))
                .toList();
    }

    public List<YearMonth> availableMonths() {
        return List.copyOf(transactions.findMonths());
    }

    private Collection<Transaction> rowsOf(YearMonth month) {
        Objects.requireNonNull(month, "month");
        Collection<Transaction> rows = transactions.findByMonth(month);
        if (rows.isEmpty()) {
            throw new MonthNotFoundException(month, availableMonths());
        }
        return rows;
    }

    private static CategorySpending categorySpending(Category category, Collection<Transaction> rows,
                                                     BigDecimal spent) {
        BigDecimal amount = totalAmd(rows);
        return new CategorySpending(category, amount, Percent.of(amount, spent), rows.size());
    }

    private static MerchantSpending merchantSpending(String merchant, Collection<Transaction> rows, BigDecimal spent) {
        BigDecimal amount = totalAmd(rows);
        return new MerchantSpending(merchant, mainCategory(rows), amount, Percent.of(amount, spent), rows.size());
    }

    private static Category mainCategory(Collection<Transaction> merchantRows) {
        return groupBy(merchantRows, Transaction::category).entrySet().stream()
                .max(Comparator.comparing((Map.Entry<Category, List<Transaction>> entry) -> totalAmd(entry.getValue()))
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElseThrow();
    }

    private static <K> Map<K, List<Transaction>> groupBy(Collection<Transaction> rows,
                                                         Function<Transaction, K> key) {
        return rows.stream().collect(Collectors.groupingBy(key));
    }

    private static List<Transaction> expensesOf(Collection<Transaction> rows) {
        return rows.stream().filter(t -> t.direction() == Direction.EXPENSE).toList();
    }

    private static List<Transaction> incomeOf(Collection<Transaction> rows) {
        return rows.stream().filter(t -> t.direction() == Direction.INCOME).toList();
    }

    private static BigDecimal totalAmd(Collection<Transaction> rows) {
        return rows.stream().map(Transaction::amountAmd).reduce(ZERO_AMD, BigDecimal::add);
    }
}
