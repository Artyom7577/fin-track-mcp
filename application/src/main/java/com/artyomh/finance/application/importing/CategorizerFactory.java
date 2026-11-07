package com.artyomh.finance.application.importing;

import com.artyomh.finance.application.port.CategoryOverrideRepository;
import com.artyomh.finance.domain.categorization.Categorizer;
import com.artyomh.finance.domain.categorization.KeywordRule;
import com.artyomh.finance.domain.categorization.KnownMerchantRule;
import com.artyomh.finance.domain.categorization.MerchantOverrideRule;

import java.util.List;

/**
 * Builds a fresh chain per import so overrides saved by {@code set_category} since the last import apply.
 */
public final class CategorizerFactory {

    private final CategoryOverrideRepository overrides;
    private final KnownMerchantRule knownMerchants;
    private final KeywordRule keywords;

    public CategorizerFactory(CategoryOverrideRepository overrides, KnownMerchantRule knownMerchants,
                              KeywordRule keywords) {
        this.overrides = overrides;
        this.knownMerchants = knownMerchants;
        this.keywords = keywords;
    }

    public Categorizer create() {
        return new Categorizer(List.of(new MerchantOverrideRule(overrides.findAll()), knownMerchants, keywords));
    }
}
