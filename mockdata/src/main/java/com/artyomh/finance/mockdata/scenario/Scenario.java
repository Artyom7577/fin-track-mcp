package com.artyomh.finance.mockdata.scenario;

import com.artyomh.finance.domain.money.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a fake statement contains: its period, opening balances and money flows; randomness comes from the generator.
 */
public record Scenario(YearMonth firstMonth, YearMonth lastMonth, String cardMask,
                       Map<Currency, BigDecimal> openingBalances, List<ScenarioEntry> entries,
                       List<Malformation> malformations) {

    // only an already-masked card ever exists in mock data, never a full card number
    private static final Pattern CARD_MASK = Pattern.compile("\\d{4}\\*{4}\\d{4}");

    public Scenario {
        Objects.requireNonNull(firstMonth, "firstMonth");
        Objects.requireNonNull(lastMonth, "lastMonth");
        Objects.requireNonNull(cardMask, "cardMask");
        if (firstMonth.isAfter(lastMonth)) {
            throw new IllegalArgumentException("firstMonth must not be after lastMonth");
        }
        if (!CARD_MASK.matcher(cardMask).matches()) {
            throw new IllegalArgumentException("cardMask must look like 4578****1234");
        }
        openingBalances = normalized(openingBalances);
        entries = List.copyOf(entries);
        malformations = List.copyOf(malformations);
        for (ScenarioEntry entry : entries) {
            validate(entry, firstMonth, lastMonth, openingBalances);
        }
    }

    public static Builder builder(YearMonth firstMonth, YearMonth lastMonth) {
        return new Builder(firstMonth, lastMonth);
    }

    public List<YearMonth> months() {
        return Stream.iterate(firstMonth, month -> !month.isAfter(lastMonth), month -> month.plusMonths(1)).toList();
    }

    private static Map<Currency, BigDecimal> normalized(Map<Currency, BigDecimal> balances) {
        Map<Currency, BigDecimal> copy = new EnumMap<>(Currency.class);
        balances.forEach((currency, balance) -> {
            if (balance.signum() < 0) {
                throw new IllegalArgumentException("opening balance must not be negative: " + currency);
            }
            copy.put(currency, balance.setScale(currency.minorDigits(), RoundingMode.UNNECESSARY));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void validate(ScenarioEntry entry, YearMonth first, YearMonth last,
                                 Map<Currency, BigDecimal> openingBalances) {
        if (!openingBalances.containsKey(entry.currency())) {
            throw new IllegalArgumentException("no opening balance for " + entry.currency() + ": " + entry.payee());
        }
        switch (entry) {
            case ScenarioEntry.Planted planted -> {
                YearMonth month = YearMonth.from(planted.date());
                if (month.isBefore(first) || month.isAfter(last)) {
                    throw new IllegalArgumentException("planted entry outside the period: " + planted.date());
                }
            }
            case ScenarioEntry.Recurring recurring -> {
                if (recurring.firstMonth().isBefore(first) || recurring.lastMonth().isAfter(last)) {
                    throw new IllegalArgumentException("recurring entry outside the period: " + recurring.payee());
                }
            }
            case ScenarioEntry.Variable _ -> {
            }
        }
    }

    public static final class Builder {

        private final YearMonth firstMonth;
        private final YearMonth lastMonth;
        private final Map<Currency, BigDecimal> openingBalances = new EnumMap<>(Currency.class);
        private final List<ScenarioEntry> entries = new ArrayList<>();
        private final List<Malformation> malformations = new ArrayList<>();
        private String cardMask;

        private Builder(YearMonth firstMonth, YearMonth lastMonth) {
            this.firstMonth = firstMonth;
            this.lastMonth = lastMonth;
        }

        public Builder cardMask(String mask) {
            this.cardMask = mask;
            return this;
        }

        public Builder openingBalance(Currency currency, BigDecimal balance) {
            openingBalances.put(currency, balance);
            return this;
        }

        public Builder entry(ScenarioEntry entry) {
            entries.add(entry);
            return this;
        }

        public Builder malformations(Collection<Malformation> kinds) {
            malformations.addAll(kinds);
            return this;
        }

        public Scenario build() {
            return new Scenario(firstMonth, lastMonth, cardMask, openingBalances, entries, malformations);
        }
    }
}
