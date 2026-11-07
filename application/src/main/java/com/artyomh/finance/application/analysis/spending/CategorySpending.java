package com.artyomh.finance.application.analysis.spending;

import com.artyomh.finance.domain.transaction.Category;

import java.math.BigDecimal;

public record CategorySpending(Category category, BigDecimal amountAmd, BigDecimal sharePct, int count) {
}
