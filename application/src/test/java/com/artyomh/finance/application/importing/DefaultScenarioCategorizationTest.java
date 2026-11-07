package com.artyomh.finance.application.importing;

import com.artyomh.finance.application.fake.InMemoryCategoryOverrideRepository;
import com.artyomh.finance.domain.categorization.CategorizationInput;
import com.artyomh.finance.domain.categorization.Categorizer;
import com.artyomh.finance.domain.categorization.DefaultCategorizationData;
import com.artyomh.finance.domain.categorization.KeywordRule;
import com.artyomh.finance.domain.categorization.KnownMerchantRule;
import com.artyomh.finance.domain.merchant.MerchantNameCleaner;
import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.mockdata.generator.GeneratedRow;
import com.artyomh.finance.mockdata.generator.StatementGenerator;
import com.artyomh.finance.mockdata.scenario.Scenarios;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Collectors;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultScenarioCategorizationTest {

    // the intended category of every merchant the default scenario produces, as the cleaner names it
    private static final Map<Payment, Category> INTENDED = Map.ofEntries(
            entry(income("TRANSFER SALARY ACME LLC"), Category.SALARY),
            entry(income("TRANSFER UPWORK ESCROW INC"), Category.FREELANCE),
            entry(expense("TRANSFER RENT"), Category.RENT),
            entry(expense("GOLDS GYM"), Category.HEALTH_FITNESS),
            entry(expense("NETFLIX.COM"), Category.SUBSCRIPTIONS),
            entry(expense("UCOM"), Category.INTERNET_MOBILE),
            entry(expense("VIVA ARMENIA"), Category.INTERNET_MOBILE),
            entry(expense("SPOTIFY"), Category.SUBSCRIPTIONS),
            entry(expense("APPLE.COM/BILL"), Category.SUBSCRIPTIONS),
            entry(expense("OPENAI *CHATGPT SUBSCR"), Category.SUBSCRIPTIONS),
            entry(expense("ENA"), Category.UTILITIES),
            entry(expense("VEOLIA JUR"), Category.UTILITIES),
            entry(expense("GAZPROM ARMENIA"), Category.UTILITIES),
            entry(expense("SAS SUPERMARKET"), Category.GROCERIES),
            entry(expense("YEREVAN CITY"), Category.GROCERIES),
            entry(expense("CARREFOUR"), Category.GROCERIES),
            entry(expense("YANDEX GO"), Category.TRANSPORT),
            entry(expense("GG TAXI"), Category.TRANSPORT),
            entry(expense("MAX PETROL"), Category.TRANSPORT),
            entry(expense("COFFEESHOP COMPANY"), Category.CAFES_RESTAURANTS),
            entry(expense("TASHIR PIZZA"), Category.CAFES_RESTAURANTS),
            entry(expense("NATALI PHARM"), Category.HEALTH_FITNESS),
            entry(expense("WILDBERRIES"), Category.SHOPPING),
            entry(expense("VEGA ELECTRONICS"), Category.SHOPPING),
            entry(expense("ZARA"), Category.SHOPPING),
            entry(expense("EVOCA ATM"), Category.CASH),
            entry(expense("BOOKING.COM"), Category.TRAVEL),
            // known limitation: a refund counts as income, not as less shopping
            entry(income("WILDBERRIES"), Category.OTHER_INCOME));

    private final Categorizer categorizer = new CategorizerFactory(new InMemoryCategoryOverrideRepository(), List.of(
            new KnownMerchantRule(DefaultCategorizationData.knownMerchants()),
            new KeywordRule(DefaultCategorizationData.keywords()))).create();

    @Test
    void everyDefaultScenarioMerchantGetsItsIntendedCategory() {
        Map<Payment, String> descriptions = new StatementGenerator()
                .generate(Scenarios.defaultSixMonths(), RandomGeneratorFactory.of("L64X128MixRandom").create(1L))
                .rows().stream()
                .collect(Collectors.toMap(DefaultScenarioCategorizationTest::paymentOf, GeneratedRow::description,
                        (first, _) -> first));

        assertThat(descriptions.keySet()).containsExactlyInAnyOrderElementsOf(INTENDED.keySet());
        descriptions.forEach((payment, description) -> assertThat(categorize(payment, description))
                .as(payment.toString())
                .isEqualTo(INTENDED.get(payment)));
    }

    @Test
    void onlyTheRefundFallsBack() {
        Set<Payment> fallbacks = INTENDED.entrySet().stream()
                .filter(intended -> intended.getValue() == Category.fallbackFor(intended.getKey().direction()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        assertThat(fallbacks).containsExactly(income("WILDBERRIES"));
    }

    private Category categorize(Payment payment, String description) {
        return categorizer.categorize(new CategorizationInput(description, payment.merchant(), payment.direction()));
    }

    private static Payment paymentOf(GeneratedRow row) {
        return new Payment(MerchantNameCleaner.clean(row.description()), row.direction());
    }

    private static Payment income(String merchant) {
        return new Payment(merchant, Direction.INCOME);
    }

    private static Payment expense(String merchant) {
        return new Payment(merchant, Direction.EXPENSE);
    }

    private record Payment(String merchant, Direction direction) {
    }
}
