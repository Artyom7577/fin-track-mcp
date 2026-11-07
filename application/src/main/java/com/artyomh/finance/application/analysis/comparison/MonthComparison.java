package com.artyomh.finance.application.analysis.comparison;

import com.artyomh.finance.application.analysis.spending.MonthlySpending;

import java.math.BigDecimal;
import java.util.List;

public record MonthComparison(MonthlySpending monthA, MonthlySpending monthB, List<CategoryChange> changes) {

    public MonthComparison {
        changes = List.copyOf(changes);
    }

    public BigDecimal totalDeltaAmd() {
        return monthB.spentAmd().subtract(monthA.spentAmd());
    }
}
