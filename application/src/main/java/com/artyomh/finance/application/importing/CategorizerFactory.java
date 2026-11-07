package com.artyomh.finance.application.importing;

import com.artyomh.finance.application.port.CategoryOverrideRepository;
import com.artyomh.finance.domain.categorization.CategorizationRule;
import com.artyomh.finance.domain.categorization.Categorizer;
import com.artyomh.finance.domain.categorization.MerchantOverrideRule;

import java.util.ArrayList;
import java.util.List;
import java.util.SequencedCollection;

/**
 * Builds a fresh chain per import: the user's overrides first, then the configured rules in their given order.
 */
public final class CategorizerFactory {

    private final CategoryOverrideRepository overrides;
    private final SequencedCollection<CategorizationRule> rules;

    public CategorizerFactory(CategoryOverrideRepository overrides, SequencedCollection<CategorizationRule> rules) {
        this.overrides = overrides;
        this.rules = List.copyOf(rules);
    }

    public Categorizer create() {
        // reloaded per import so overrides saved by set_category since the last import apply
        List<CategorizationRule> chain = new ArrayList<>();
        chain.add(new MerchantOverrideRule(overrides.findAll()));
        chain.addAll(rules);
        return new Categorizer(chain);
    }
}
