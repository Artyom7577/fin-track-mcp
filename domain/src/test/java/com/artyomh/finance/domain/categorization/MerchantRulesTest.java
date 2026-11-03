package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Direction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class MerchantRulesTest {

    @Test
    void knownMerchantMatchesRegardlessOfCaseAndPunctuation() {
        KnownMerchantRule rule = new KnownMerchantRule(Map.of("Yerevan City", Category.GROCERIES));

        assertThat(rule.apply(expense("YEREVAN-CITY"))).contains(Category.GROCERIES);
    }

    @Test
    void unknownMerchantDefersToTheNextRule() {
        KnownMerchantRule rule = new KnownMerchantRule(Map.of("YEREVAN CITY", Category.GROCERIES));

        assertThat(rule.apply(expense("YEREVAN CITY MALL"))).isEmpty();
    }

    @Test
    void overrideKeysAreNormalizedLikeTheInput() {
        MerchantOverrideRule rule = new MerchantOverrideRule(Map.of("sas  supermarket", Category.SHOPPING));

        assertThat(rule.apply(expense("SAS SUPERMARKET"))).contains(Category.SHOPPING);
    }

    @Test
    void twoSpellingsOfOneMerchantFailFast() {
        assertThatIllegalStateException().isThrownBy(() -> new KnownMerchantRule(
                Map.of("SAS", Category.GROCERIES, "sas", Category.SHOPPING)));
    }

    private static CategorizationInput expense(String merchant) {
        return new CategorizationInput(merchant, merchant, Direction.EXPENSE);
    }
}
