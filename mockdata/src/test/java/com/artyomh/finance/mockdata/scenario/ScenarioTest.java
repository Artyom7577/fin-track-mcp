package com.artyomh.finance.mockdata.scenario;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry.Planted;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry.Recurring;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry.Variable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ScenarioTest {

    private static final YearMonth MAY = YearMonth.of(2026, Month.MAY);
    private static final YearMonth JUNE = YearMonth.of(2026, Month.JUNE);
    private static final Payee SHOP = new Payee("SHOP", Channel.POS);
    private static final Money TEN_AMD = new Money(BigDecimal.TEN, Currency.AMD);
    private static final Money TWENTY_AMD = new Money(new BigDecimal("20"), Currency.AMD);

    @Test
    void defaultScenarioCoversMayToOctober() {
        Scenario scenario = Scenarios.defaultSixMonths();

        assertThat(scenario.months()).hasSize(6).startsWith(MAY).endsWith(YearMonth.of(2026, Month.OCTOBER));
        assertThat(scenario.malformations()).isEmpty();
        assertThat(scenario.openingBalances())
                .containsEntry(Currency.AMD, new BigDecimal("400000.00"))
                .containsEntry(Currency.USD, new BigDecimal("250.00"));
    }

    @Test
    void malformedVariantListsEveryKindOnce() {
        assertThat(Scenarios.withMalformedRows().malformations()).containsExactly(Malformation.values());
    }

    @Test
    void rejectsPlantedEntryOutsideThePeriod() {
        Scenario.Builder builder = mayOnly().entry(
                new Planted(SHOP, Direction.EXPENSE, TEN_AMD, LocalDate.of(2026, Month.JUNE, 1)));

        assertThatIllegalArgumentException().isThrownBy(builder::build).withMessageContaining("outside the period");
    }

    @Test
    void rejectsRecurringEntryOutsideThePeriod() {
        Scenario.Builder builder = mayOnly().entry(new Recurring(SHOP, Direction.EXPENSE, TEN_AMD, 1, MAY, JUNE));

        assertThatIllegalArgumentException().isThrownBy(builder::build).withMessageContaining("outside the period");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4578****123", "4578XXXX1234", ""})
    void rejectsAnythingButAMaskedCard(String mask) {
        Scenario.Builder builder = mayOnly().cardMask(mask);

        assertThatIllegalArgumentException().isThrownBy(builder::build);
    }

    @Test
    void rejectsAFullCardNumber() {
        // built at runtime so no card-number-shaped literal sits in the repository for scanners to flag
        String fullNumber = "4578" + "1234".repeat(3);
        Scenario.Builder builder = mayOnly().cardMask(fullNumber);

        assertThatIllegalArgumentException().isThrownBy(builder::build);
    }

    @Test
    void rejectsNegativeOpeningBalance() {
        Scenario.Builder builder = mayOnly().openingBalance(Currency.USD, new BigDecimal("-1"));

        assertThatIllegalArgumentException().isThrownBy(builder::build);
    }

    @Test
    void rejectsEntryWhoseCurrencyHasNoOpeningBalance() {
        Scenario.Builder builder = Scenario.builder(MAY, MAY).cardMask("4578****1234")
                .openingBalance(Currency.AMD, BigDecimal.ZERO)
                .entry(new Planted(SHOP, Direction.EXPENSE, new Money(BigDecimal.ONE, Currency.USD), MAY.atDay(2)));

        assertThatIllegalArgumentException().isThrownBy(builder::build).withMessageContaining("USD");
    }

    @Test
    void rejectsLastMonthBeforeFirst() {
        Scenario.Builder builder = Scenario.builder(JUNE, MAY).cardMask("4578****1234");

        assertThatIllegalArgumentException().isThrownBy(builder::build);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 32})
    void rejectsDayOutsideOneToThirtyOne(int day) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Recurring(SHOP, Direction.EXPENSE, TEN_AMD, day, MAY, MAY));
    }

    @Test
    void amountRangeAllowsMinEqualToMax() {
        assertThat(new AmountSource.Between(TEN_AMD, TEN_AMD).currency()).isEqualTo(Currency.AMD);
    }

    @Test
    void amountRangeRejectsMinAboveMaxAndMixedCurrencies() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AmountSource.Between(TWENTY_AMD, TEN_AMD));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AmountSource.Between(TEN_AMD, new Money(BigDecimal.TEN, Currency.USD)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"15", "1009.50"})
    void amountsMustBeWholePriceSteps(String amount) {
        Money uneven = new Money(new BigDecimal(amount), Currency.AMD);

        assertThatIllegalArgumentException().isThrownBy(() -> new AmountSource.Between(TEN_AMD, uneven))
                .withMessageContaining("multiple of 10");
        assertThatIllegalArgumentException().isThrownBy(() -> new AmountSource.OneOf(List.of(TEN_AMD, uneven)));
    }

    @Test
    void usdAmountsMustBeWholeDollars() {
        Money cents = new Money(new BigDecimal("9.99"), Currency.USD);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AmountSource.Between(cents, new Money(BigDecimal.TEN, Currency.USD)));
    }

    @Test
    void choicesMustBeNonEmptyAndShareACurrency() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AmountSource.OneOf(List.of()));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AmountSource.OneOf(List.of(TEN_AMD, new Money(BigDecimal.TEN, Currency.USD))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.4", "0.5"})
    void rejectsAMultiplierThatCanRoundTheSmallestAmountToZero(String factor) {
        AmountSource tenDrams = new AmountSource.Between(TEN_AMD, TWENTY_AMD);
        Map<Month, BigDecimal> multipliers = Map.of(Month.MAY, new BigDecimal(factor));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Variable(SHOP, Direction.EXPENSE, tenDrams, Frequency.times(1, 1), multipliers))
                .withMessageContaining("round to zero");
    }

    @Test
    void acceptsAMultiplierThatStillRoundsToAWholeStep() {
        AmountSource tenDrams = new AmountSource.OneOf(List.of(TWENTY_AMD, TEN_AMD));

        assertThat(new Variable(SHOP, Direction.EXPENSE, tenDrams, Frequency.times(1, 1),
                Map.of(Month.MAY, new BigDecimal("0.6"))).multiplierFor(Month.MAY)).isEqualByComparingTo("0.6");
    }

    @Test
    void frequencyRejectsInvalidBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> Frequency.times(-1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> Frequency.times(3, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> Frequency.oncePerMonthWithChance(0));
        assertThatIllegalArgumentException().isThrownBy(() -> Frequency.oncePerMonthWithChance(101));
    }

    @Test
    void multiplierDefaultsToOneAndMustBePositive() {
        Variable variable = new Variable(SHOP, Direction.EXPENSE, new AmountSource.Between(TEN_AMD, TEN_AMD),
                Frequency.times(1, 1), Map.of(Month.OCTOBER, new BigDecimal("1.6")));

        assertThat(variable.multiplierFor(Month.MAY)).isEqualByComparingTo("1");
        assertThat(variable.multiplierFor(Month.OCTOBER)).isEqualByComparingTo("1.6");
        assertThatIllegalArgumentException().isThrownBy(() -> new Variable(SHOP, Direction.EXPENSE,
                new AmountSource.Between(TEN_AMD, TEN_AMD), Frequency.times(1, 1), Map.of(Month.MAY, BigDecimal.ZERO)));
    }

    private static Scenario.Builder mayOnly() {
        return Scenario.builder(MAY, MAY).cardMask("4578****1234").openingBalance(Currency.AMD, BigDecimal.ZERO);
    }
}
