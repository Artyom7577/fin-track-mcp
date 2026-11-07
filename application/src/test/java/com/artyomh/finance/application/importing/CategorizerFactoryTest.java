package com.artyomh.finance.application.importing;

import com.artyomh.finance.application.fake.InMemoryCategoryOverrideRepository;
import com.artyomh.finance.domain.categorization.CategorizationInput;
import com.artyomh.finance.domain.categorization.Categorizer;
import com.artyomh.finance.domain.categorization.KnownMerchantRule;
import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CategorizerFactoryTest {

    private static final CategorizationInput SHOP = new CategorizationInput("POS SHOP", "SHOP", Direction.EXPENSE);

    private final InMemoryCategoryOverrideRepository overrides = new InMemoryCategoryOverrideRepository();
    private final CategorizerFactory factory = new CategorizerFactory(overrides, List.of(
            new KnownMerchantRule(Map.of("SHOP", Category.GROCERIES)),
            new KnownMerchantRule(Map.of("SHOP", Category.SHOPPING))));

    @Test
    void earlierConfiguredRuleWins() {
        assertThat(factory.create().categorize(SHOP)).isEqualTo(Category.GROCERIES);
    }

    @Test
    void overrideBeatsEveryConfiguredRule() {
        overrides.save("SHOP", Category.TRAVEL);

        assertThat(factory.create().categorize(SHOP)).isEqualTo(Category.TRAVEL);
    }

    @Test
    void eachCreateSeesOverridesSavedSinceTheLastOne() {
        Categorizer before = factory.create();
        overrides.save("SHOP", Category.TRAVEL);

        assertThat(before.categorize(SHOP)).isEqualTo(Category.GROCERIES);
        assertThat(factory.create().categorize(SHOP)).isEqualTo(Category.TRAVEL);
    }
}
