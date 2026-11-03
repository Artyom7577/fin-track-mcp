package com.artyomh.finance.domain.merchant;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MerchantNameCleaner {

    private static final List<Pattern> NOISE = List.of(
            Pattern.compile("^((POS|ONLINE|ATM|E-COM|PURCHASE)\\s+)+"),
            Pattern.compile("(?<!\\S)\\d{0,6}[*X]{2,}\\d{4}(?!\\S)"),
            Pattern.compile("\\s+(YEREVAN|GYUMRI|VANADZOR|DILIJAN)\\s+AM$"),
            Pattern.compile("\\s+AM$"),
            Pattern.compile("\\s+(PAYMENT|TOP-UP)$")
    );

    private static final Pattern FULL_CARD_NUMBER = Pattern.compile(
            "(?<!\\d)(?:\\d[ -]?){8,15}(\\d{4})(?!\\d)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String MASKED_CARD_NUMBER = "****$1";
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private MerchantNameCleaner() {
    }

    public static String clean(String description) {
        Objects.requireNonNull(description, "description");
        String original = FULL_CARD_NUMBER.matcher(collapseSpaces(description).toUpperCase(Locale.ROOT))
                .replaceAll(MASKED_CARD_NUMBER);
        String name = original;
        for (Pattern noise : NOISE) {
            name = collapseSpaces(noise.matcher(name).replaceAll(" "));
        }
        if (name.isEmpty()) {
            return original;
        }
        return name;
    }

    private static String collapseSpaces(String text) {
        return SPACES.matcher(text.strip()).replaceAll(" ");
    }
}
