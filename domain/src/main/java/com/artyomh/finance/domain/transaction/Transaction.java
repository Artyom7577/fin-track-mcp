package com.artyomh.finance.domain.transaction;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

public record Transaction(TransactionId id,
                          LocalDate date,
                          String description,
                          String merchant,
                          Direction direction,
                          Money money,
                          BigDecimal amountAmd,
                          BigDecimal exchangeRate,
                          Category category,
                          String sourceFile) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(money, "money");
        Objects.requireNonNull(amountAmd, "amountAmd");
        Objects.requireNonNull(exchangeRate, "exchangeRate");
        Objects.requireNonNull(category, "category");
        requireText(description, "description");
        requireText(merchant, "merchant");
        requireText(sourceFile, "sourceFile");

        if (category.direction() != direction) {
            throw new IllegalArgumentException(
                    "category %s is %s but transaction is %s".formatted(category, category.direction(), direction));
        }
        if (exchangeRate.signum() <= 0) {
            throw new IllegalArgumentException("exchangeRate must be positive: " + exchangeRate.toPlainString());
        }
        if (money.currency() == Currency.AMD && exchangeRate.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("AMD transactions must have exchangeRate 1");
        }
        amountAmd = toAmdScale(amountAmd);
        if (amountAmd.compareTo(toAmdScale(money.amount().multiply(exchangeRate))) != 0) {
            throw new IllegalArgumentException("amountAmd %s does not equal %s × %s".formatted(
                    amountAmd.toPlainString(), money.amount().toPlainString(), exchangeRate.toPlainString()));
        }
    }

    public YearMonth month() {
        return YearMonth.from(date);
    }

    public boolean isExpense() {
        return direction == Direction.EXPENSE;
    }

    public Transaction withCategory(Category newCategory) {
        return new Transaction(id, date, description, merchant, direction, money,
                amountAmd, exchangeRate, newCategory, sourceFile);
    }

    private static BigDecimal toAmdScale(BigDecimal amount) {
        return amount.setScale(Currency.AMD.minorDigits(), RoundingMode.HALF_EVEN);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
