package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Direction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;

class CategorizerTest {

    private final Categorizer categorizer = new Categorizer(List.of(
            new MerchantOverrideRule(Map.of("SAS SUPERMARKET", Category.SHOPPING)),
            new KnownMerchantRule(Map.of("SAS SUPERMARKET", Category.GROCERIES, "ZARA", Category.SHOPPING)),
            new KeywordRule(keywords())));

    @Test
    void overrideBeatsKnownMerchant() {
        assertThat(categorizer.categorize(expense("SAS SUPERMARKET"))).isEqualTo(Category.SHOPPING);
    }

    @Test
    void knownMerchantBeatsKeyword() {
        assertThat(categorizer.categorize(new CategorizationInput("ZARA SUPERMARKET", "ZARA", Direction.EXPENSE)))
                .isEqualTo(Category.SHOPPING);
    }

    @Test
    void keywordAnswersWhenNoMerchantRuleDoes() {
        assertThat(categorizer.categorize(expense("CITY SUPERMARKET"))).isEqualTo(Category.GROCERIES);
    }

    @Test
    void noAnswerFallsBackByDirection() {
        assertThat(categorizer.categorize(expense("UNKNOWN SHOP"))).isEqualTo(Category.OTHER);
        assertThat(categorizer.categorize(income("UNKNOWN SENDER"))).isEqualTo(Category.OTHER_INCOME);
    }

    @Test
    void answerOfTheWrongDirectionIsSkipped() {
        assertThat(categorizer.categorize(income("SAS SUPERMARKET"))).isEqualTo(Category.OTHER_INCOME);
    }

    @Test
    void wrongDirectionAnswerLetsTheNextRuleAnswer() {
        Categorizer chain = new Categorizer(List.of(
                input -> Optional.of(Category.GROCERIES),
                input -> Optional.of(Category.SALARY)));

        assertThat(chain.categorize(income("EMPLOYER"))).isEqualTo(Category.SALARY);
    }

    @Test
    void emptyChainAlwaysFallsBack() {
        assertThat(new Categorizer(List.of()).categorize(expense("ANY"))).isEqualTo(Category.OTHER);
    }

    private static SequencedMap<Category, List<String>> keywords() {
        SequencedMap<Category, List<String>> keywords = new LinkedHashMap<>();
        keywords.put(Category.GROCERIES, List.of("SUPERMARKET"));
        return keywords;
    }

    private static CategorizationInput expense(String merchant) {
        return new CategorizationInput(merchant, merchant, Direction.EXPENSE);
    }

    private static CategorizationInput income(String merchant) {
        return new CategorizationInput(merchant, merchant, Direction.INCOME);
    }
}
