package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class MerchantOverrideRule implements CategorizationRule {

    private final Map<String, Category> categoryByMerchant;

    public MerchantOverrideRule(Map<String, Category> categoryByMerchant) {
        this.categoryByMerchant = categoryByMerchant.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> CategorizationInput.normalize(entry.getKey()), Map.Entry::getValue));
    }

    @Override
    public Optional<Category> apply(CategorizationInput input) {
        return Optional.ofNullable(categoryByMerchant.get(input.merchant()));
    }
}
