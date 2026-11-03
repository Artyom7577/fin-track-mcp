package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.regex.Pattern;

public final class KeywordRule implements CategorizationRule {

    // longest first, so "BOLT FOOD" wins over "BOLT"; ties keep the map's order
    private static final Comparator<Keyword> LONGEST_FIRST =
            Comparator.comparingInt((Keyword keyword) -> keyword.text().length()).reversed();

    private final List<Keyword> keywords;

    public KeywordRule(SequencedMap<Category, ? extends Collection<String>> keywordsByCategory) {
        this.keywords = keywordsByCategory.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(word -> keyword(word, entry.getKey())))
                .sorted(LONGEST_FIRST)
                .toList();
    }

    @Override
    public Optional<Category> apply(CategorizationInput input) {
        return keywords.stream()
                .filter(keyword -> keyword.pattern().matcher(input.description()).find())
                .map(Keyword::category)
                .findFirst();
    }

    private static Keyword keyword(String word, Category category) {
        String text = CategorizationInput.normalize(word);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("blank keyword for " + category);
        }
        // "ENA" must not match inside "OPENAI"
        Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(text) + "(?![\\p{L}\\p{N}])");
        return new Keyword(text, pattern, category);
    }

    private record Keyword(String text, Pattern pattern, Category category) {
    }
}
