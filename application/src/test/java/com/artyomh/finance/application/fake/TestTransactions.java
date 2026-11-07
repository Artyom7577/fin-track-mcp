package com.artyomh.finance.application.fake;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Transaction;
import com.artyomh.finance.domain.transaction.TransactionId;
import com.artyomh.finance.domain.transaction.TransactionKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestTransactions {

    private static final BigDecimal USD_RATE = new BigDecimal("385.00");
    private static final AtomicInteger OCCURRENCE = new AtomicInteger();

    private TestTransactions() {
    }

    public static Transaction amd(LocalDate date, String merchant, Category category, String amount) {
        return of(date, merchant, category, new Money(new BigDecimal(amount), Currency.AMD), BigDecimal.ONE);
    }

    public static Transaction usd(LocalDate date, String merchant, Category category, String amount) {
        return of(date, merchant, category, new Money(new BigDecimal(amount), Currency.USD), USD_RATE);
    }

    private static Transaction of(LocalDate date, String merchant, Category category, Money money, BigDecimal rate) {
        TransactionKey key = new TransactionKey(date, merchant, category.direction(), money);
        return new Transaction(TransactionId.of(key, OCCURRENCE.getAndIncrement()), date, merchant, merchant,
                category.direction(), money, money.amount().multiply(rate), rate, category, "test.xlsx");
    }
}
