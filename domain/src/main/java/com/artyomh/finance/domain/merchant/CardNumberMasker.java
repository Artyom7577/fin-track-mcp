package com.artyomh.finance.domain.merchant;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Replaces every full card number with {@code ****} plus its last four digits, so a PAN is never stored or hashed.
 */
public final class CardNumberMasker {

    private static final Pattern FULL_CARD_NUMBER = Pattern.compile(
            "(?<!\\d)(?:\\d[ -]?){8,15}(\\d{4})(?!\\d)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String MASKED_CARD_NUMBER = "****$1";

    private CardNumberMasker() {
    }

    public static String mask(String text) {
        Objects.requireNonNull(text, "text");
        return FULL_CARD_NUMBER.matcher(text).replaceAll(MASKED_CARD_NUMBER);
    }
}
