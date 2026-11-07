package com.artyomh.finance.mockdata.generator;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.mockdata.scenario.Channel;
import com.artyomh.finance.mockdata.scenario.Malformation;
import com.artyomh.finance.mockdata.scenario.Payee;
import com.artyomh.finance.mockdata.scenario.Scenario;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry.Planted;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry.Recurring;
import com.artyomh.finance.mockdata.scenario.Scenarios;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatementGeneratorTest {

    private static final long SEED = 20260501L;
    private static final int SEEDS = 200;

    private final StatementGenerator generator = new StatementGenerator();
    private final GeneratedStatement statement = generate(Scenarios.defaultSixMonths(), SEED);

    @Test
    void sameSeedGivesTheSameStatement() {
        assertThat(generate(Scenarios.defaultSixMonths(), SEED)).isEqualTo(statement);
    }

    @Test
    void differentSeedGivesADifferentStatement() {
        assertThat(generate(Scenarios.defaultSixMonths(), SEED + 1)).isNotEqualTo(statement);
    }

    @Test
    void octoberStoriesArePlanted() {
        assertThat(statement.rows())
                .anySatisfy(row -> assertRow(row, "2026-10-18",
                        "POS 4578****1234 VEGA ELECTRONICS YEREVAN AM", "189000"))
                .anySatisfy(row -> assertRow(row, "2026-10-11", "ONLINE BOOKING.COM", "112000"))
                .anySatisfy(row -> assertRow(row, "2026-10-24", "POS 4578****1234 ZARA YEREVAN AM", "46000"));
    }

    @Test
    void octoberSpendingJumps() {
        Map<YearMonth, BigDecimal> spent = amdSpentByMonth(statement);
        BigDecimal october = spent.get(YearMonth.of(2026, Month.OCTOBER));

        assertThat(spent).allSatisfy((month, amount) -> {
            if (month.getMonth() != Month.OCTOBER) {
                assertThat(october).isGreaterThan(amount);
            }
        });
    }

    @Test
    void plantedDuplicateTaxiPairIsPresent() {
        List<GeneratedRow> taxis = statement.rows().stream()
                .filter(row -> row.date().equals(LocalDate.of(2026, Month.AUGUST, 22)))
                .filter(row -> row.description().equals("POS 4578****1234 YANDEX GO YEREVAN AM"))
                .filter(row -> row.money().equals(amd("1400")))
                .toList();

        assertThat(taxis).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void refundIsIncomingMoneyFromTheSameMerchantText() {
        assertThat(statement.rows())
                .anySatisfy(row -> {
                    assertRow(row, "2026-09-16", "ONLINE WILDBERRIES", "12500");
                    assertThat(row.direction()).isEqualTo(Direction.INCOME);
                })
                .anySatisfy(row -> {
                    assertThat(row.description()).isEqualTo("ONLINE WILDBERRIES");
                    assertThat(row.direction()).isEqualTo(Direction.EXPENSE);
                });
    }

    @Test
    void spotifyStopsAfterAugust() {
        List<Month> months = statement.rows().stream()
                .filter(row -> row.description().equals("ONLINE SPOTIFY"))
                .map(row -> row.date().getMonth())
                .toList();

        assertThat(months).containsExactly(Month.MAY, Month.JUNE, Month.JULY, Month.AUGUST);
    }

    @Test
    void gasCostsMoreInOctober() {
        assertThat(statement.rows())
                .filteredOn(row -> row.description().equals("ONLINE GAZPROM ARMENIA PAYMENT"))
                .hasSize(6)
                .allSatisfy(row -> {
                    BigDecimal amount = row.money().amount();
                    if (row.date().getMonth() == Month.OCTOBER) {
                        assertThat(amount).isBetween(new BigDecimal("6400"), new BigDecimal("11200"));
                    } else {
                        assertThat(amount).isBetween(new BigDecimal("4000"), new BigDecimal("7000"));
                    }
                });
    }

    @Test
    void coffeeAlwaysCostsTheSameAndRepeats() {
        assertThat(statement.rows())
                .filteredOn(row -> row.description().contains("COFFEESHOP COMPANY"))
                .hasSizeGreaterThanOrEqualTo(6 * 8)
                .allSatisfy(row -> assertThat(row.money()).isEqualTo(amd("1800")));
    }

    @Test
    void atmDispensesOnlyRoundAmounts() {
        assertThat(statement.rows())
                .filteredOn(row -> row.description().startsWith("ATM "))
                .hasSize(6)
                .allSatisfy(row -> assertThat(row.money()).isIn(amd("20000"), amd("40000"), amd("60000")));
    }

    @Test
    void randomAmdAmountsAreWholeTensOfDrams() {
        assertThat(statement.rows())
                .filteredOn(row -> row.money().currency() == Currency.AMD)
                .allSatisfy(row -> assertThat(row.money().amount().remainder(BigDecimal.TEN)).isZero());
    }

    @Test
    void rowsAreOrderedByDateThenScenarioOrder() {
        LocalDate day = LocalDate.of(2026, Month.MAY, 2);
        Scenario scenario = mayScenario(List.of(
                new Planted(new Payee("FIRST", Channel.ONLINE), Direction.EXPENSE, amd("10"), day.plusDays(1)),
                new Planted(new Payee("SECOND", Channel.ONLINE), Direction.EXPENSE, amd("10"), day),
                new Planted(new Payee("THIRD", Channel.ONLINE), Direction.EXPENSE, amd("10"), day)));

        assertThat(generate(scenario, SEED).rows()).extracting(GeneratedRow::description)
                .containsExactly("ONLINE SECOND", "ONLINE THIRD", "ONLINE FIRST");
    }

    @Test
    void recurringDayPastMonthEndFallsOnItsLastDay() {
        YearMonth june = YearMonth.of(2026, Month.JUNE);
        Scenario scenario = Scenario.builder(june, june).cardMask("4578****1234")
                .openingBalance(Currency.AMD, new BigDecimal("1000"))
                .entry(new Recurring(new Payee("RENT", Channel.TRANSFER), Direction.EXPENSE, amd("10"), 31,
                        june, june))
                .build();

        assertThat(generate(scenario, SEED).rows()).extracting(GeneratedRow::date)
                .containsExactly(LocalDate.of(2026, Month.JUNE, 30));
    }

    @Test
    void balancesFollowFromOpeningBalancesAndEveryRow() {
        Map<Currency, BigDecimal> running = new EnumMap<>(Scenarios.defaultSixMonths().openingBalances());
        for (GeneratedRow row : statement.rows()) {
            BigDecimal signed = row.direction() == Direction.INCOME
                    ? row.money().amount()
                    : row.money().amount().negate();
            BigDecimal expected = running.merge(row.money().currency(), signed, BigDecimal::add);
            assertThat(row.balanceAfter()).isEqualTo(expected);
        }
    }

    @Test
    void neitherCurrencyGoesNegativeAcrossSeeds() {
        IntStream.range(0, SEEDS).forEach(seed -> {
            GeneratedStatement generated = generate(Scenarios.defaultSixMonths(), seed);
            assertThat(minimumBalance(generated, Currency.USD)).as("USD, seed %d", seed).isNotNegative();
            assertThat(minimumBalance(generated, Currency.AMD)).as("AMD, seed %d", seed).isNotNegative();
        });
    }

    @Test
    void averageMonthlySpendMatchesTheDocumentedEstimate() {
        double regular = IntStream.range(0, SEEDS)
                .mapToObj(seed -> amdSpentByMonth(generate(Scenarios.defaultSixMonths(), seed)))
                .flatMap(spent -> spent.entrySet().stream())
                .filter(entry -> entry.getKey().getMonth() != Month.OCTOBER)
                .mapToDouble(entry -> entry.getValue().doubleValue())
                .average()
                .orElseThrow();

        assertThat(regular).isCloseTo(692_000, within(692_000 * 0.02));
    }

    @Test
    void malformedRowsAreInsertedOnceEachWithoutTouchingValidRows() {
        GeneratedStatement malformed = generate(Scenarios.withMalformedRows(), SEED);

        assertThat(malformed.rows()).isEqualTo(statement.rows());
        assertThat(malformed.lines()).hasSize(statement.rows().size() + Malformation.values().length);
        assertThat(malformed.lines().getFirst()).isInstanceOf(GeneratedRow.class);
        assertThat(malformed.lines().getLast()).isInstanceOf(GeneratedRow.class);
        List<MalformedRow> broken = malformed.lines().stream()
                .filter(MalformedRow.class::isInstance)
                .map(MalformedRow.class::cast)
                .toList();
        assertThat(broken).extracting(MalformedRow::date).contains("31.02.2026");
        assertThat(broken).extracting(MalformedRow::debit).contains("125OO", "");
        assertThat(broken).extracting(MalformedRow::currency).contains("EUR");
    }

    private GeneratedStatement generate(Scenario scenario, long seed) {
        return generator.generate(scenario, random(seed));
    }

    private static RandomGenerator random(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    private static Scenario mayScenario(Iterable<Planted> entries) {
        YearMonth may = YearMonth.of(2026, Month.MAY);
        Scenario.Builder builder = Scenario.builder(may, may).cardMask("4578****1234")
                .openingBalance(Currency.AMD, new BigDecimal("1000"));
        entries.forEach(builder::entry);
        return builder.build();
    }

    private static Map<YearMonth, BigDecimal> amdSpentByMonth(GeneratedStatement statement) {
        Map<YearMonth, BigDecimal> spent = new TreeMap<>();
        statement.rows().stream()
                .filter(row -> row.direction() == Direction.EXPENSE && row.money().currency() == Currency.AMD)
                .forEach(row -> spent.merge(YearMonth.from(row.date()), row.money().amount(), BigDecimal::add));
        return spent;
    }

    private static BigDecimal minimumBalance(GeneratedStatement statement, Currency currency) {
        return statement.rows().stream()
                .filter(row -> row.money().currency() == currency)
                .map(GeneratedRow::balanceAfter)
                .min(BigDecimal::compareTo)
                .orElseThrow();
    }

    private static void assertRow(GeneratedRow row, String date, String description, String amd) {
        assertThat(row.date()).isEqualTo(LocalDate.parse(date));
        assertThat(row.description()).isEqualTo(description);
        assertThat(row.money()).isEqualTo(amd(amd));
    }

    private static Money amd(String amount) {
        return new Money(new BigDecimal(amount), Currency.AMD);
    }
}
