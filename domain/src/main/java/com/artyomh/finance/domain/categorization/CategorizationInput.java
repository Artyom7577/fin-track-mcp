package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Direction;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record CategorizationInput(String description, String merchant, Direction direction) {

    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    public CategorizationInput {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(direction, "direction");
        description = normalize(description);
        merchant = normalize(merchant);
    }

    /**
     * The single text form rules compare: "Bolt*Food " and "BOLT FOOD" are equal.
     */
    static String normalize(String text) {
        String upper = Normalizer.normalize(text, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        return NOT_LETTER_OR_DIGIT.matcher(upper).replaceAll(" ").strip();
    }
}
