package com.artyomh.finance.mockdata.generator;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.mockdata.scenario.AmountSource;
import com.artyomh.finance.mockdata.scenario.Channel;
import com.artyomh.finance.mockdata.scenario.Malformation;
import com.artyomh.finance.mockdata.scenario.Payee;
import com.artyomh.finance.mockdata.scenario.Scenario;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Expands a scenario into dated rows with running balances; the same scenario and seed give the same statement.
 */
public final class StatementGenerator {

    private static final int PERCENT = 100;
    private static final String CITY_SUFFIX = " YEREVAN AM";
    private static final Payee MALFORMED_PAYEE = new Payee("SAS SUPERMARKET", Channel.POS);
    private static final String MALFORMED_AMOUNT = "5000.00";
    private static final String IMPOSSIBLE_DAY = "31.02.";
    private static final String TEXT_AMOUNT = "125OO";
    // unsupported only because Currency has AMD and USD
    private static final String UNSUPPORTED_CURRENCY = "EUR";

    public GeneratedStatement generate(Scenario scenario, RandomGenerator random) {
        List<Draft> drafts = new ArrayList<>();
        for (YearMonth month : scenario.months()) {
            for (ScenarioEntry entry : scenario.entries()) {
                expand(entry, month, random, drafts);
            }
        }
        drafts.sort(Comparator.comparing(Draft::date).thenComparingInt(Draft::sequence));
        List<GeneratedRow> rows = withBalances(drafts, scenario);
        return new GeneratedStatement(withMalformations(rows, scenario, random));
    }

    private static void expand(ScenarioEntry entry, YearMonth month, RandomGenerator random, Collection<Draft> drafts) {
        switch (entry) {
            case ScenarioEntry.Recurring recurring -> {
                if (recurring.isActiveIn(month)) {
                    LocalDate date = month.atDay(Math.min(recurring.dayOfMonth(), month.lengthOfMonth()));
                    drafts.add(draft(drafts, date, recurring, recurring.amount()));
                }
            }
            case ScenarioEntry.Variable variable -> {
                if (random.nextInt(PERCENT) < variable.frequency().chancePercent()) {
                    int times = random.nextInt(variable.frequency().minTimes(), variable.frequency().maxTimes() + 1);
                    for (int i = 0; i < times; i++) {
                        LocalDate date = month.atDay(random.nextInt(1, month.lengthOfMonth() + 1));
                        Money amount = scaled(pick(variable.amounts(), random),
                                variable.multiplierFor(month.getMonth()));
                        drafts.add(draft(drafts, date, variable, amount));
                    }
                }
            }
            case ScenarioEntry.Planted planted -> {
                if (YearMonth.from(planted.date()).equals(month)) {
                    drafts.add(draft(drafts, planted.date(), planted, planted.amount()));
                }
            }
        }
    }

    private static Draft draft(Collection<Draft> drafts, LocalDate date, ScenarioEntry entry, Money amount) {
        return new Draft(date, drafts.size(), entry.payee(), entry.direction(), amount);
    }

    private static Money pick(AmountSource amounts, RandomGenerator random) {
        return switch (amounts) {
            case AmountSource.Between(Money min, Money max) -> {
                BigDecimal step = AmountSource.stepOf(min.currency());
                long steps = max.amount().subtract(min.amount()).divideToIntegralValue(step).longValueExact();
                BigDecimal offset = step.multiply(BigDecimal.valueOf(random.nextLong(steps + 1)));
                yield new Money(min.amount().add(offset), min.currency());
            }
            case AmountSource.OneOf(List<Money> choices) -> choices.get(random.nextInt(choices.size()));
        };
    }

    private static Money scaled(Money amount, BigDecimal multiplier) {
        BigDecimal scaled = amount.amount().multiply(multiplier);
        return new Money(AmountSource.roundToStep(scaled, amount.currency()), amount.currency());
    }

    private static List<GeneratedRow> withBalances(Collection<Draft> drafts, Scenario scenario) {
        Map<Currency, BigDecimal> balances = new EnumMap<>(Currency.class);
        balances.putAll(scenario.openingBalances());
        List<GeneratedRow> rows = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            Money money = draft.amount();
            BigDecimal signed = switch (draft.direction()) {
                case INCOME -> money.amount();
                case EXPENSE -> money.amount().negate();
            };
            BigDecimal balance = balances.merge(money.currency(), signed, BigDecimal::add);
            rows.add(new GeneratedRow(draft.date(), description(draft.payee(), scenario.cardMask()),
                    draft.direction(), money, balance));
        }
        return rows;
    }

    private static String description(Payee payee, String cardMask) {
        return switch (payee.channel()) {
            case POS -> "POS " + cardMask + " " + payee.name() + CITY_SUFFIX;
            case ATM -> "ATM " + cardMask + " " + payee.name() + CITY_SUFFIX;
            case ONLINE -> "ONLINE " + payee.name();
            case TRANSFER -> "TRANSFER " + payee.name();
        };
    }

    private static List<StatementLine> withMalformations(List<GeneratedRow> rows, Scenario scenario,
                                                         RandomGenerator random) {
        if (scenario.malformations().isEmpty()) {
            return List.copyOf(rows);
        }
        if (rows.size() < 2) {
            throw new IllegalArgumentException("malformed rows need at least two valid rows around them");
        }
        Map<Integer, List<MalformedRow>> before = new HashMap<>();
        String description = description(MALFORMED_PAYEE, scenario.cardMask());
        for (Malformation kind : scenario.malformations()) {
            // never first or last, so the parser must recover and carry on
            int index = random.nextInt(1, rows.size());
            LocalDate date = rows.get(index).date();
            before.computeIfAbsent(index, _ -> new ArrayList<>()).add(malformed(kind, date, description));
        }
        List<StatementLine> lines = new ArrayList<>(rows.size() + scenario.malformations().size());
        for (int i = 0; i < rows.size(); i++) {
            lines.addAll(before.getOrDefault(i, List.of()));
            lines.add(rows.get(i));
        }
        return lines;
    }

    private static MalformedRow malformed(Malformation kind, LocalDate date, String description) {
        String day = GeneratedStatement.DATE_FORMAT.format(date);
        String amd = Currency.AMD.name();
        return switch (kind) {
            case BAD_DATE -> new MalformedRow(IMPOSSIBLE_DAY + date.getYear(), description, MALFORMED_AMOUNT, "", amd);
            case TEXT_IN_AMOUNT -> new MalformedRow(day, description, TEXT_AMOUNT, "", amd);
            case MISSING_AMOUNT -> new MalformedRow(day, description, "", "", amd);
            case UNSUPPORTED_CURRENCY ->
                    new MalformedRow(day, description, MALFORMED_AMOUNT, "", UNSUPPORTED_CURRENCY);
        };
    }

    private record Draft(LocalDate date, int sequence, Payee payee, Direction direction, Money amount) {
    }
}
