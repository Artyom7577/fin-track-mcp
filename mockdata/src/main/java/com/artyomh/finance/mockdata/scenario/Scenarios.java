package com.artyomh.finance.mockdata.scenario;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry.Planted;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry.Recurring;
import com.artyomh.finance.mockdata.scenario.ScenarioEntry.Variable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Named scenarios; the default spends about 692,000 AMD a month (1,042,000 in October), its USD opening balance
 * covers every subscription, and AMD staying non-negative is verified across 200 seeds.
 */
public final class Scenarios {

    private static final YearMonth FIRST = YearMonth.of(2026, Month.MAY);
    private static final YearMonth LAST = YearMonth.of(2026, Month.OCTOBER);
    private static final YearMonth LAST_SPOTIFY = YearMonth.of(2026, Month.AUGUST);
    private static final String CARD_MASK = "4578****1234";
    private static final BigDecimal WINTER_GAS = new BigDecimal("1.6");

    private static final Payee SALARY = new Payee("SALARY ACME LLC", Channel.TRANSFER);
    private static final Payee RENT = new Payee("RENT PAYMENT", Channel.TRANSFER);
    private static final Payee NETFLIX = new Payee("NETFLIX.COM", Channel.ONLINE);
    private static final Payee YANDEX_GO = new Payee("YANDEX GO", Channel.POS);
    private static final Payee WILDBERRIES = new Payee("WILDBERRIES", Channel.ONLINE);
    private static final Payee COFFEESHOP = new Payee("COFFEESHOP COMPANY", Channel.POS);

    private Scenarios() {
    }

    public static Scenario defaultSixMonths() {
        return defaultBuilder().build();
    }

    public static Scenario withMalformedRows() {
        return defaultBuilder().malformations(Arrays.asList(Malformation.values())).build();
    }

    /**
     * A small, seed-independent sample whose written files are committed as the writers' golden files.
     */
    public static Scenario goldenSample() {
        YearMonth may = FIRST;
        return Scenario.builder(may, may)
                .cardMask(CARD_MASK)
                .openingBalance(Currency.AMD, new BigDecimal("100000"))
                .openingBalance(Currency.USD, new BigDecimal("20"))
                .entry(new Recurring(SALARY, Direction.INCOME, amd("850000"), 1, may, may))
                .entry(new Recurring(RENT, Direction.EXPENSE, amd("250000"), 3, may, may))
                .entry(new Recurring(NETFLIX, Direction.EXPENSE, usd("9.99"), 7, may, may))
                .entry(planted(COFFEESHOP, amd("1800"), may.atDay(9)))
                .entry(planted(YANDEX_GO, amd("1400"), may.atDay(22)))
                .entry(planted(YANDEX_GO, amd("1400"), may.atDay(22)))
                .entry(planted(WILDBERRIES, amd("18500"), may.atDay(26)))
                .entry(new Planted(WILDBERRIES, Direction.INCOME, amd("12500"), may.atDay(28)))
                .build();
    }

    private static Scenario.Builder defaultBuilder() {
        return Scenario.builder(FIRST, LAST)
                .cardMask(CARD_MASK)
                .openingBalance(Currency.AMD, new BigDecimal("400000"))
                .openingBalance(Currency.USD, new BigDecimal("250"))
                .entry(monthly(SALARY, Direction.INCOME, amd("850000"), 1))
                .entry(new Variable(new Payee("UPWORK ESCROW INC", Channel.TRANSFER), Direction.INCOME,
                        between(usd("300"), usd("900")), Frequency.oncePerMonthWithChance(50)))
                .entry(monthly(RENT, Direction.EXPENSE, amd("250000"), 3))
                .entry(monthly(new Payee("GOLDS GYM", Channel.POS), Direction.EXPENSE, amd("25000"), 5))
                .entry(monthly(NETFLIX, Direction.EXPENSE, usd("9.99"), 7))
                .entry(monthly(new Payee("UCOM", Channel.ONLINE), Direction.EXPENSE, amd("8900"), 10))
                .entry(monthly(new Payee("VIVA ARMENIA", Channel.ONLINE), Direction.EXPENSE, amd("5500"), 12))
                .entry(new Recurring(new Payee("SPOTIFY", Channel.ONLINE), Direction.EXPENSE, usd("5.99"), 14,
                        FIRST, LAST_SPOTIFY))
                .entry(monthly(new Payee("APPLE.COM/BILL", Channel.ONLINE), Direction.EXPENSE, usd("2.99"), 20))
                .entry(monthly(new Payee("OPENAI *CHATGPT SUBSCR", Channel.ONLINE), Direction.EXPENSE,
                        usd("20.00"), 25))
                .entry(variable(new Payee("ENA PAYMENT", Channel.ONLINE), between(amd("9000"), amd("16000")),
                        Frequency.times(1, 1)))
                .entry(variable(new Payee("VEOLIA JUR PAYMENT", Channel.ONLINE), between(amd("3000"), amd("5000")),
                        Frequency.times(1, 1)))
                .entry(new Variable(new Payee("GAZPROM ARMENIA PAYMENT", Channel.ONLINE), Direction.EXPENSE,
                        between(amd("4000"), amd("7000")), Frequency.times(1, 1), Map.of(Month.OCTOBER, WINTER_GAS)))
                .entry(variable(new Payee("SAS SUPERMARKET", Channel.POS), between(amd("6000"), amd("35000")),
                        Frequency.times(4, 7)))
                .entry(variable(new Payee("YEREVAN CITY", Channel.POS), between(amd("6000"), amd("35000")),
                        Frequency.times(2, 4)))
                .entry(variable(new Payee("CARREFOUR", Channel.POS), between(amd("6000"), amd("35000")),
                        Frequency.times(1, 3)))
                .entry(variable(YANDEX_GO, between(amd("700"), amd("2500")), Frequency.times(6, 10)))
                .entry(variable(new Payee("GG TAXI", Channel.POS), between(amd("700"), amd("2500")),
                        Frequency.times(2, 5)))
                .entry(variable(COFFEESHOP, between(amd("1800"), amd("1800")), Frequency.times(8, 12)))
                .entry(variable(new Payee("TASHIR PIZZA", Channel.POS), between(amd("4000"), amd("12000")),
                        Frequency.times(1, 3)))
                .entry(variable(new Payee("NATALI PHARM", Channel.POS), between(amd("2000"), amd("9000")),
                        Frequency.times(0, 2)))
                .entry(variable(new Payee("MAX PETROL", Channel.POS), between(amd("15000"), amd("25000")),
                        Frequency.times(2, 3)))
                .entry(variable(WILDBERRIES, between(amd("5000"), amd("30000")), Frequency.times(0, 2)))
                .entry(variable(new Payee("EVOCA ATM", Channel.ATM),
                        new AmountSource.OneOf(List.of(amd("20000"), amd("40000"), amd("60000"))), Frequency.times(1, 1)))
                .entry(planted(new Payee("BOOKING.COM", Channel.ONLINE), amd("112000"), LAST.atDay(11)))
                .entry(planted(new Payee("VEGA ELECTRONICS", Channel.POS), amd("189000"), LAST.atDay(18)))
                .entry(planted(new Payee("ZARA", Channel.POS), amd("46000"), LAST.atDay(24)))
                .entry(planted(YANDEX_GO, amd("1400"), LocalDate.of(2026, Month.AUGUST, 22)))
                .entry(planted(YANDEX_GO, amd("1400"), LocalDate.of(2026, Month.AUGUST, 22)))
                .entry(new Planted(WILDBERRIES, Direction.INCOME, amd("12500"),
                        LocalDate.of(2026, Month.SEPTEMBER, 16)));
    }

    private static Recurring monthly(Payee payee, Direction direction, Money amount, int dayOfMonth) {
        return new Recurring(payee, direction, amount, dayOfMonth, FIRST, LAST);
    }

    private static Variable variable(Payee payee, AmountSource amounts, Frequency frequency) {
        return new Variable(payee, Direction.EXPENSE, amounts, frequency);
    }

    private static Planted planted(Payee payee, Money amount, LocalDate date) {
        return new Planted(payee, Direction.EXPENSE, amount, date);
    }

    private static AmountSource between(Money min, Money max) {
        return new AmountSource.Between(min, max);
    }

    private static Money amd(String amount) {
        return new Money(new BigDecimal(amount), Currency.AMD);
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), Currency.USD);
    }
}
