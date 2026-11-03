package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;

import java.util.List;
import java.util.Optional;
import java.util.SequencedCollection;

/**
 * Asks each rule in order; the first answer matching the transaction's direction wins, else its fallback.
 */
public final class Categorizer {

    private final List<CategorizationRule> rules;

    public Categorizer(SequencedCollection<CategorizationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public Category categorize(CategorizationInput input) {
        return rules.stream()
                .map(rule -> rule.apply(input))
                .flatMap(Optional::stream)
                .filter(category -> category.direction() == input.direction())
                .findFirst()
                .orElse(Category.fallbackFor(input.direction()));
    }
}
