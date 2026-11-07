package com.artyomh.finance.application.analysis.spending;

import com.artyomh.finance.domain.transaction.Category;

import java.math.BigDecimal;

public record MerchantSpending(String merchant, Category mainCategory, BigDecimal amountAmd, BigDecimal sharePct,
                               int count) {
}
