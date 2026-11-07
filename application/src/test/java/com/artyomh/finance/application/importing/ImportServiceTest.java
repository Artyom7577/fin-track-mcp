package com.artyomh.finance.application.importing;

import com.artyomh.finance.application.fake.InMemoryCategoryOverrideRepository;
import com.artyomh.finance.application.fake.InMemoryTransactionRepository;
import com.artyomh.finance.application.port.ExchangeRateProvider;
import com.artyomh.finance.domain.categorization.KeywordRule;
import com.artyomh.finance.domain.categorization.KnownMerchantRule;
import com.artyomh.finance.domain.money.Currency;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Direction;
import com.artyomh.finance.domain.transaction.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportServiceTest {

    private static final Path FILE = Path.of("/statements/october.xlsx");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);
    private static final BigDecimal USD_RATE = new BigDecimal("385.00");

    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final InMemoryCategoryOverrideRepository overrides = new InMemoryCategoryOverrideRepository();
    private final List<ParsedRow> rows = new ArrayList<>();
    private final Collection<RowProblem> problems = new ArrayList<>();
    private final ImportService service = new ImportService(
            file -> new ParseResult(rows, problems), repository, fixedUsdRate(), categorizers());

    @Test
    void newRowsAreAddedAndCategorized() {
        rows.add(amd(1, "POS 4578****1234 SAS SUPERMARKET YEREVAN AM", "5000"));

        ImportSummary summary = service.importFile(FILE);

        assertThat(summary.added()).isEqualTo(1);
        assertThat(stored()).singleElement().satisfies(tx -> {
            assertThat(tx.merchant()).isEqualTo("SAS SUPERMARKET");
            assertThat(tx.category()).isEqualTo(Category.GROCERIES);
            assertThat(tx.sourceFile()).isEqualTo("october.xlsx");
        });
    }

    @Test
    void importingTheSameStatementTwiceAddsNothing() {
        rows.add(amd(1, "SAS SUPERMARKET", "5000"));
        rows.add(amd(2, "CAFE JAZZVE", "1200"));

        ImportSummary first = service.importFile(FILE);
        ImportSummary second = service.importFile(FILE);

        assertThat(first.added()).isEqualTo(2);
        assertThat(second.added()).isZero();
        assertThat(second.duplicates()).isEqualTo(2);
        assertThat(stored()).hasSize(2);
    }

    @Test
    void identicalRowsOnOneDayAreBothImported() {
        rows.add(amd(1, "CAFE JAZZVE", "1200"));
        rows.add(amd(2, "CAFE JAZZVE", "1200"));

        ImportSummary summary = service.importFile(FILE);

        assertThat(summary.added()).isEqualTo(2);
        assertThat(summary.duplicates()).isZero();
    }

    @Test
    void identicalRowsDifferingOnlyInCaseOrSpacingShareTheOccurrenceCounter() {
        rows.add(amd(1, "cafe  jazzve", "1200"));
        rows.add(amd(2, "CAFE JAZZVE", "1200"));

        service.importFile(FILE);
        ImportSummary again = service.importFile(FILE);

        assertThat(stored()).hasSize(2);
        assertThat(again.duplicates()).isEqualTo(2);
    }

    @Test
    void rejectedRowsAreReportedAndEveryRowIsAccountedFor() {
        rows.add(amd(1, "SAS SUPERMARKET", "5000"));
        problems.add(new RowProblem(2, "UNPARSEABLE_AMOUNT"));
        service.importFile(FILE);
        rows.add(amd(3, "CAFE JAZZVE", "1200"));

        ImportSummary summary = service.importFile(FILE);

        assertThat(summary.rejected()).containsExactly(new RowProblem(2, "UNPARSEABLE_AMOUNT"));
        assertThat(summary.added() + summary.duplicates() + summary.rejected().size()).isEqualTo(summary.rowsRead());
        assertThat(summary.rowsRead()).isEqualTo(3);
    }

    @Test
    void usdIsConvertedWithTheProviderRate() {
        rows.add(new ParsedRow(1, DATE, "NETFLIX.COM", Direction.EXPENSE,
                new Money(new BigDecimal("9.99"), Currency.USD)));

        service.importFile(FILE);

        Transaction tx = stored().iterator().next();
        assertThat(tx.exchangeRate()).isEqualByComparingTo(USD_RATE);
        assertThat(tx.amountAmd()).isEqualByComparingTo("3846.15");
    }

    @Test
    void amdNeverAsksTheRateProvider() {
        ImportService strict = new ImportService(file -> new ParseResult(rows, problems), repository,
                (currency, date) -> {
                    throw new AssertionError("rate requested for " + currency);
                }, categorizers());
        rows.add(amd(1, "SAS SUPERMARKET", "5000"));

        ImportSummary summary = strict.importFile(FILE);

        assertThat(summary.added()).isEqualTo(1);
    }

    @Test
    void overrideSavedBetweenImportsApplies() {
        overrides.save("CAFE JAZZVE", Category.SHOPPING);
        rows.add(amd(1, "CAFE JAZZVE", "1200"));

        service.importFile(FILE);

        assertThat(stored()).singleElement()
                .extracting(Transaction::category).isEqualTo(Category.SHOPPING);
    }

    @Test
    void incomeRowFallsBackToOtherIncome() {
        rows.add(new ParsedRow(1, DATE, "SAS SUPERMARKET REFUND", Direction.INCOME,
                new Money(new BigDecimal("5000"), Currency.AMD)));

        service.importFile(FILE);

        assertThat(stored()).singleElement()
                .extracting(Transaction::category).isEqualTo(Category.OTHER_INCOME);
    }

    @Test
    void fullCardNumberInDescriptionIsMaskedBeforeStoringAndHashing() {
        rows.add(amd(1, "POS 4578123456789012 SAS SUPERMARKET", "5000"));
        service.importFile(FILE);
        rows.set(0, amd(1, "POS ****9012 SAS SUPERMARKET", "5000"));

        ImportSummary again = service.importFile(FILE);

        assertThat(stored()).singleElement()
                .extracting(Transaction::description).isEqualTo("POS ****9012 SAS SUPERMARKET");
        assertThat(again.duplicates()).isEqualTo(1);
    }

    @Test
    void emptyStatementAddsNothing() {
        ImportSummary summary = service.importFile(FILE);

        assertThat(summary).isEqualTo(new ImportSummary("october.xlsx", 0, 0, 0, List.of()));
    }

    @Test
    void blankDescriptionCannotReachTheService() {
        assertThatThrownBy(() -> amd(1, "  ", "5000")).isInstanceOf(IllegalArgumentException.class);
    }

    private Collection<Transaction> stored() {
        return repository.findBetween(LocalDate.MIN, LocalDate.MAX);
    }

    private CategorizerFactory categorizers() {
        SequencedMap<Category, List<String>> keywords = new LinkedHashMap<>();
        keywords.put(Category.GROCERIES, List.of("SUPERMARKET"));
        return new CategorizerFactory(overrides, List.of(new KnownMerchantRule(Map.of()), new KeywordRule(keywords)));
    }

    private static ExchangeRateProvider fixedUsdRate() {
        return (currency, date) -> USD_RATE;
    }

    private static ParsedRow amd(int line, String description, String amount) {
        return new ParsedRow(line, DATE, description, Direction.EXPENSE, new Money(new BigDecimal(amount), Currency.AMD));
    }
}
