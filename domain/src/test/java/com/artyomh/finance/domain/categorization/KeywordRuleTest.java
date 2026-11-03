package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KeywordRuleTest {

    private final KeywordRule rule = new KeywordRule(keywords());

    @ParameterizedTest
    @CsvSource({
            "ENA ELECTRICITY PAYMENT,          UTILITIES",
            "payment to ena,                   UTILITIES",
            "POS 4578****1234 SAS SUPERMARKET, GROCERIES",
            "BOLT FOOD ORDER,                  CAFES_RESTAURANTS",
            "BOLT*FOOD,                        CAFES_RESTAURANTS",
            "BOLT RIDE,                        TRANSPORT",
            "ԴԵՂԱՏՈՒՆ NATALI PHARM,            HEALTH_FITNESS",
    })
    void wholeWordKeywordMatches(String description, Category expected) {
        assertThat(rule.apply(expense(description))).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI *CHATGPT SUBSCR", "SCENARIO STUDIO", "ENA2", "SASUNA", "ԱԴԵՂԱՏՈՒՆ"})
    void keywordInsideAnotherWordDoesNotMatch(String description) {
        assertThat(rule.apply(expense(description))).isEmpty();
    }

    @Test
    void blankKeywordIsRejected() {
        SequencedMap<Category, List<String>> blank = new LinkedHashMap<>();
        blank.put(Category.OTHER, List.of(" * "));

        assertThatIllegalArgumentException().isThrownBy(() -> new KeywordRule(blank));
    }

    private static SequencedMap<Category, List<String>> keywords() {
        SequencedMap<Category, List<String>> keywords = new LinkedHashMap<>();
        keywords.put(Category.TRANSPORT, List.of("BOLT"));
        keywords.put(Category.UTILITIES, List.of("ENA"));
        keywords.put(Category.GROCERIES, List.of("SAS"));
        keywords.put(Category.CAFES_RESTAURANTS, List.of("bolt food"));
        keywords.put(Category.HEALTH_FITNESS, List.of("ԴԵՂԱՏՈՒՆ"));
        return keywords;
    }

    private static CategorizationInput expense(String description) {
        return new CategorizationInput(description, description, Direction.EXPENSE);
    }
}
