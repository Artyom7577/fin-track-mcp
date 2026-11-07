package com.artyomh.finance.application.analysis.comparison;

import com.artyomh.finance.application.analysis.Percent;
import com.artyomh.finance.domain.transaction.Category;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record CategoryChange(Category category, BigDecimal amountAAmd, BigDecimal amountBAmd) {

    public CategoryChange {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(amountAAmd, "amountAAmd");
        Objects.requireNonNull(amountBAmd, "amountBAmd");
    }

    public BigDecimal deltaAmd() {
        return amountBAmd.subtract(amountAAmd);
    }

    /**
     * Empty when the category had no spending in month A, where a relative change is undefined.
     */
    public Optional<BigDecimal> changePct() {
        if (amountAAmd.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(Percent.of(deltaAmd(), amountAAmd));
    }
}
