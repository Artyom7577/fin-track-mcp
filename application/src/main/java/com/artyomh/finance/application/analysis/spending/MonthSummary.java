package com.artyomh.finance.application.analysis.spending;

import java.math.BigDecimal;
import java.time.YearMonth;

public record MonthSummary(YearMonth month, BigDecimal incomeAmd, BigDecimal spentAmd, int count) {

    public BigDecimal netAmd() {
        return incomeAmd.subtract(spentAmd);
    }
}
