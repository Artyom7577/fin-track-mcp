package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;

import java.util.Optional;

@FunctionalInterface
public interface CategorizationRule {
    Optional<Category> apply(CategorizationInput input);
}