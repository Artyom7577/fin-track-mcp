package com.artyomh.finance.application.analysis.comparison;

import com.artyomh.finance.application.analysis.spending.CategorySpending;
import com.artyomh.finance.application.analysis.spending.MonthlySpending;
import com.artyomh.finance.application.analysis.spending.SpendingService;
import com.artyomh.finance.application.exception.InvalidInputException;
import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.transaction.Category;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ComparisonService {

    private static final BigDecimal ZERO_AMD = BigDecimal.ZERO.setScale(Currency.AMD.minorDigits());
    private static final Comparator<CategoryChange> LARGEST_CHANGE_FIRST =
            Comparator.comparing((CategoryChange change) -> change.deltaAmd().abs()).reversed()
                    .thenComparing(CategoryChange::category);

    private final SpendingService spending;

    public ComparisonService(SpendingService spending) {
        this.spending = Objects.requireNonNull(spending, "spending");
    }

    public MonthComparison compareMonths(YearMonth monthA, YearMonth monthB) {
        Objects.requireNonNull(monthA, "monthA");
        Objects.requireNonNull(monthB, "monthB");
        if (monthA.equals(monthB)) {
            throw new InvalidInputException("monthA and monthB must differ, both are " + monthA);
        }
        MonthlySpending a = spending.spendingByCategory(monthA);
        MonthlySpending b = spending.spendingByCategory(monthB);
        return new MonthComparison(a, b, changesBetween(a, b));
    }

    private static List<CategoryChange> changesBetween(MonthlySpending a, MonthlySpending b) {
        Map<Category, BigDecimal> amountsA = amountsByCategory(a);
        Map<Category, BigDecimal> amountsB = amountsByCategory(b);
        Set<Category> categories = EnumSet.noneOf(Category.class);
        categories.addAll(amountsA.keySet());
        categories.addAll(amountsB.keySet());
        return categories.stream()
                .map(category -> new CategoryChange(category, amountsA.getOrDefault(category, ZERO_AMD),
                        amountsB.getOrDefault(category, ZERO_AMD)))
                .sorted(LARGEST_CHANGE_FIRST)
                .toList();
    }

    private static Map<Category, BigDecimal> amountsByCategory(MonthlySpending month) {
        return month.categories().stream()
                .collect(Collectors.toMap(CategorySpending::category, CategorySpending::amountAmd));
    }
}
