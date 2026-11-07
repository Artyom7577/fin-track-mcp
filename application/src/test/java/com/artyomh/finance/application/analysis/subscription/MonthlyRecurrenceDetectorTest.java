package com.artyomh.finance.application.analysis.subscription;

import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static com.artyomh.finance.application.fake.TestTransactions.amd;
import static com.artyomh.finance.application.fake.TestTransactions.usd;
import static com.artyomh.finance.domain.transaction.Category.CAFES_RESTAURANTS;
import static com.artyomh.finance.domain.transaction.Category.INTERNET_MOBILE;
import static com.artyomh.finance.domain.transaction.Category.SHOPPING;
import static com.artyomh.finance.domain.transaction.Category.SUBSCRIPTIONS;
import static org.assertj.core.api.Assertions.assertThat;

class MonthlyRecurrenceDetectorTest {

    static final SubscriptionThresholds DEFAULTS =
            new SubscriptionThresholds(3, new BigDecimal("15"), 12, 1, new BigDecimal("1.5"));
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    private final MonthlyRecurrenceDetector detector = new MonthlyRecurrenceDetector(DEFAULTS);

    @Test
    void sameChargeInThreeMonthsIsASubscription() {
        List<Transaction> charges = monthly("NETFLIX", "5000", 8, 9, 10);

        List<Subscription> found = detector.detect(charges, OCTOBER);

        assertThat(found).singleElement().satisfies(s -> {
            assertThat(s.merchant()).isEqualTo("NETFLIX");
            assertThat(s.typicalAmount()).isEqualTo(new Money(new BigDecimal("5000"), Currency.AMD));
            assertThat(s.monthlyAmd()).isEqualByComparingTo("5000");
            assertThat(s.yearlyAmd()).isEqualByComparingTo("60000");
            assertThat(s.firstSeen()).isEqualTo(YearMonth.of(2026, 8));
            assertThat(s.lastCharged()).isEqualTo(LocalDate.of(2026, 10, 3));
            assertThat(s.months()).isEqualTo(3);
            assertThat(s.status()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(s.kind()).isEqualTo(SubscriptionKind.SUBSCRIPTION);
        });
    }

    @Test
    void twoMonthsAreNotEnough() {
        assertThat(detector.detect(monthly("NETFLIX", "5000", 9, 10), OCTOBER)).isEmpty();
    }

    @Test
    void skippedMonthStillCounts() {
        assertThat(detector.detect(monthly("NETFLIX", "5000", 6, 8, 10), OCTOBER)).hasSize(1);
    }

    @Test
    void variationUpToFifteenPercentIsStable() {
        List<Transaction> charges = List.of(charge("GYM", "10000", 8), charge("GYM", "11500", 9),
                charge("GYM", "10000", 10));

        assertThat(detector.detect(charges, OCTOBER)).hasSize(1);
    }

    @Test
    void variationAboveFifteenPercentIsNotASubscription() {
        List<Transaction> charges = List.of(charge("SAS", "10000", 8), charge("SAS", "11600", 9),
                charge("SAS", "10000", 10));

        assertThat(detector.detect(charges, OCTOBER)).isEmpty();
    }

    @Test
    void variationJustAboveFifteenPercentFailsEvenIfItRoundsToFifteen() {
        List<Transaction> charges = List.of(charge("GYM", "10000", 8), charge("GYM", "11504", 9),
                charge("GYM", "10000", 10));

        assertThat(detector.detect(charges, OCTOBER)).isEmpty();
    }

    @Test
    void frequentSamePricePurchasesAreNotASubscription() {
        List<Transaction> coffee = new ArrayList<>();
        for (int month = 8; month <= 10; month++) {
            for (int day = 1; day <= 2; day++) {
                coffee.add(amd(LocalDate.of(2026, month, day), "JAZZVE", CAFES_RESTAURANTS, "1200"));
            }
        }

        assertThat(detector.detect(coffee, OCTOBER)).isEmpty();
    }

    @Test
    void oneAndAHalfChargesPerMonthIsStillAllowed() {
        List<Transaction> charges = new ArrayList<>(monthly("ICLOUD", "500", 9, 10));
        charges.addAll(monthly("ICLOUD", "500", 9));
        charges.addAll(monthly("ICLOUD", "500", 8));

        assertThat(detector.detect(charges, OCTOBER)).singleElement()
                .extracting(Subscription::monthlyAmd).isEqualTo(new BigDecimal("666.67"));
    }

    @Test
    void oldSubscriptionIsKeptAsPossiblyCancelled() {
        List<Subscription> found = detector.detect(monthly("SPOTIFY", "2000", 5, 6, 7, 8), OCTOBER);

        assertThat(found).singleElement().extracting(Subscription::status)
                .isEqualTo(SubscriptionStatus.POSSIBLY_CANCELLED);
    }

    @Test
    void chargeLastMonthIsStillActive() {
        assertThat(detector.detect(monthly("SPOTIFY", "2000", 7, 8, 9), OCTOBER)).singleElement()
                .extracting(Subscription::status).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void usdSubscriptionIsComparedInUsdAndReportedInAmd() {
        List<Transaction> charges = List.of(usd(LocalDate.of(2026, 8, 3), "OPENAI", SUBSCRIPTIONS, "20"),
                usd(LocalDate.of(2026, 9, 3), "OPENAI", SUBSCRIPTIONS, "20"),
                usd(LocalDate.of(2026, 10, 3), "OPENAI", SUBSCRIPTIONS, "20"));

        Subscription openai = detector.detect(charges, OCTOBER).getFirst();

        assertThat(openai.typicalAmount()).isEqualTo(new Money(new BigDecimal("20"), Currency.USD));
        assertThat(openai.monthlyAmd()).isEqualByComparingTo("7700");
    }

    @Test
    void evenCountMedianIsTheMeanOfTheMiddleTwo() {
        List<Transaction> charges = List.of(charge("GYM", "10000", 7), charge("GYM", "10000", 8),
                charge("GYM", "11000", 9), charge("GYM", "11000", 10));

        assertThat(detector.detect(charges, OCTOBER).getFirst().typicalAmount().amount())
                .isEqualByComparingTo("10500");
    }

    @Test
    void billsAreToldApartFromSubscriptions() {
        List<Transaction> charges = new ArrayList<>();
        for (int month = 8; month <= 10; month++) {
            charges.add(amd(LocalDate.of(2026, month, 3), "UCOM", INTERNET_MOBILE, "9000"));
        }

        assertThat(detector.detect(charges, OCTOBER).getFirst().kind()).isEqualTo(SubscriptionKind.BILL);
    }

    @Test
    void activeComeFirstThenLargestMonthlyAmount() {
        List<Transaction> charges = new ArrayList<>(monthly("SPOTIFY", "2000", 4, 5, 6));
        charges.addAll(monthly("NETFLIX", "5000", 8, 9, 10));
        charges.addAll(monthly("ICLOUD", "500", 8, 9, 10));

        assertThat(detector.detect(charges, OCTOBER)).extracting(Subscription::merchant)
                .containsExactly("NETFLIX", "ICLOUD", "SPOTIFY");
    }

    @Test
    void mainCategoryIsTheLargestForTheMerchant() {
        List<Transaction> charges = List.of(amd(LocalDate.of(2026, 8, 3), "YANDEX", SUBSCRIPTIONS, "3000"),
                amd(LocalDate.of(2026, 9, 3), "YANDEX", SUBSCRIPTIONS, "3000"),
                amd(LocalDate.of(2026, 10, 3), "YANDEX", SHOPPING, "3000"));

        assertThat(detector.detect(charges, OCTOBER).getFirst().mainCategory()).isEqualTo(SUBSCRIPTIONS);
    }

    private static List<Transaction> monthly(String merchant, String amount, int... months) {
        List<Transaction> charges = new ArrayList<>();
        for (int month : months) {
            charges.add(charge(merchant, amount, month));
        }
        return charges;
    }

    private static Transaction charge(String merchant, String amount, int month) {
        return amd(LocalDate.of(2026, month, 3), merchant, SUBSCRIPTIONS, amount);
    }
}
