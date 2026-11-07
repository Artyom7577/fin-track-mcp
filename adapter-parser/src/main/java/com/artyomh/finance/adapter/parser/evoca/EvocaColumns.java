package com.artyomh.finance.adapter.parser.evoca;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds the Evoca header by English, Armenian or Russian labels and remembers each column's position.
 */
public final class EvocaColumns {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String LABEL_PART_SEPARATOR = "/";

    enum Column {
        DATE("Date", "Ամսաթիվ", true, "date", "ամսաթիվ", "дата"),
        DESCRIPTION("Description", "Նկարագրություն", true, "description", "նկարագրություն", "описание"),
        DEBIT("Debit", "Ելք", true, "debit", "ելք", "расход", "дебет"),
        CREDIT("Credit", "Մուտք", true, "credit", "մուտք", "приход", "кредит"),
        CURRENCY("Currency", "Արժույթ", true, "currency", "արժույթ", "валюта"),
        BALANCE("Balance", "Մնացորդ", false, "balance", "մնացորդ", "остаток");

        private final String englishName;
        private final String armenianName;
        private final boolean required;
        private final Set<String> aliases;

        Column(String englishName, String armenianName, boolean required, String... aliases) {
            this.englishName = englishName;
            this.armenianName = armenianName;
            this.required = required;
            this.aliases = Set.of(aliases);
        }

        String englishName() {
            return englishName;
        }
    }

    private final Map<Column, Integer> positions;

    private EvocaColumns(Map<Column, Integer> positions) {
        this.positions = positions;
    }

    /**
     * The columns of a header row, or empty when a required column is missing or a column matches twice.
     */
    static Optional<EvocaColumns> locate(List<String> labels) {
        Map<Column, Integer> positions = new EnumMap<>(Column.class);
        for (int index = 0; index < labels.size(); index++) {
            List<Column> matches = matches(labels.get(index));
            if (matches.size() > 1) {
                return Optional.empty();
            }
            for (Column column : matches) {
                if (positions.putIfAbsent(column, index) != null) {
                    return Optional.empty();
                }
            }
        }
        boolean complete = Arrays.stream(Column.values())
                .filter(column -> column.required)
                .allMatch(positions::containsKey);
        if (!complete) {
            return Optional.empty();
        }
        return Optional.of(new EvocaColumns(positions));
    }

    public static boolean isHeader(List<String> labels) {
        return locate(labels).isPresent();
    }

    /**
     * The required columns for an error message, e.g. "Date (Ամսաթիվ), Description (Նկարագրություն), …".
     */
    public static String expected() {
        return Arrays.stream(Column.values())
                .filter(column -> column.required)
                .map(column -> column.englishName() + " (" + column.armenianName + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * Label text as matched: NFKC, whitespace collapsed and trimmed, lowercase without locale.
     */
    static String normalizeLabel(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    int position(Column column) {
        return positions.get(column);
    }

    private static List<Column> matches(String label) {
        List<String> parts = Arrays.stream(normalizeLabel(label).split(LABEL_PART_SEPARATOR))
                .map(String::strip)
                .toList();
        return Arrays.stream(Column.values())
                .filter(column -> parts.stream().anyMatch(column.aliases::contains))
                .toList();
    }
}
