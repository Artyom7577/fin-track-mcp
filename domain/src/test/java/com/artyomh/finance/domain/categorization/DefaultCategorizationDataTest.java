package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultCategorizationDataTest {

    private final Categorizer categorizer = new Categorizer(List.of(
            new KnownMerchantRule(DefaultCategorizationData.knownMerchants()),
            new KeywordRule(DefaultCategorizationData.keywords())));

    @Test
    void noKeywordIsListedUnderTwoCategories() {
        Map<String, Long> counts = DefaultCategorizationData.keywords().values().stream()
                .flatMap(List::stream)
                .map(CategorizationInput::normalize)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(counts).allSatisfy((keyword, count) -> assertThat(count).as(keyword).isEqualTo(1L));
    }

    @Test
    void noKnownMerchantCollidesAfterNormalization() {
        List<String> normalized = DefaultCategorizationData.knownMerchants().keySet().stream()
                .map(CategorizationInput::normalize)
                .toList();

        assertThat(normalized).doesNotHaveDuplicates();
    }

    @Test
    void dataIsUnmodifiable() {
        assertThatThrownBy(() -> DefaultCategorizationData.keywords().put(Category.OTHER, List.of("X")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "ONLINE OPENAI *CHATGPT SUBSCR, OPENAI *CHATGPT SUBSCR, SUBSCRIPTIONS",
            "ONLINE ENA PAYMENT, ENA, UTILITIES",
            "POS 4578****1234 COFFEESHOP COMPANY YEREVAN AM, COFFEESHOP COMPANY, CAFES_RESTAURANTS",
            "ONLINE BOOKING.COM, BOOKING.COM, TRAVEL",
            "ONLINE APPLE.COM/BILL, APPLE.COM/BILL, SUBSCRIPTIONS"
    })
    void defaultsCategorizeTypicalExpenses(String description, String merchant, Category expected) {
        assertThat(categorizer.categorize(new CategorizationInput(description, merchant, Direction.EXPENSE)))
                .isEqualTo(expected);
    }
}
