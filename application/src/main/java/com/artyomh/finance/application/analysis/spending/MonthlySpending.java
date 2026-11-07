package com.artyomh.finance.application.analysis.spending;

import com.artyomh.finance.application.analysis.Percent;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public record MonthlySpending(YearMonth month, BigDecimal spentAmd, BigDecimal incomeAmd,
                              List<CategorySpending> categories) {

    public MonthlySpending {
        categories = List.copyOf(categories);
    }

    public BigDecimal netAmd() {
        return incomeAmd.subtract(spentAmd);
    }

    /**
     * Empty when the month has no income, since a savings rate of nothing is undefined.
     */
    public Optional<BigDecimal> savingsRatePct() {
        if (incomeAmd.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(Percent.of(netAmd(), incomeAmd));
    }
}
