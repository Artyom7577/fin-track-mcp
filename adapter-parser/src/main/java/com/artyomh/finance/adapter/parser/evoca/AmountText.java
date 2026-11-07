package com.artyomh.finance.adapter.parser.evoca;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locale-free amount text: {@code .} is the only decimal point, digits group by threes with one kind of space only.
 */
final class AmountText {

    // a comma is never accepted: "1,500" is 1500 in one export and 1.5 in another, a silent 1000x error
    private static final Pattern AMOUNT = Pattern.compile(
            "-?(?:\\d+|\\d{1,3}([ \\u00A0\\u202F])\\d{3}(?:\\1\\d{3})*)(?:\\.\\d+)?");
    private static final Pattern GROUP_SEPARATOR = Pattern.compile("[ \\u00A0\\u202F]");

    private AmountText() {
    }

    /**
     * The signed amount, or empty when the text is not an amount in this grammar.
     */
    static Optional<BigDecimal> parse(String text) {
        Matcher matcher = AMOUNT.matcher(strip(text));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(GROUP_SEPARATOR.matcher(matcher.group()).replaceAll("")));
    }

    private static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
