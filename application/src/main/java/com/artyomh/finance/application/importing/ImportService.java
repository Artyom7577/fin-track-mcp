package com.artyomh.finance.application.importing;

import com.artyomh.finance.application.port.ExchangeRateProvider;
import com.artyomh.finance.application.port.StatementParser;
import com.artyomh.finance.application.port.TransactionRepository;
import com.artyomh.finance.domain.categorization.CategorizationInput;
import com.artyomh.finance.domain.categorization.Categorizer;
import com.artyomh.finance.domain.merchant.CardNumberMasker;
import com.artyomh.finance.domain.merchant.MerchantNameCleaner;
import com.artyomh.finance.domain.money.Money;
import com.artyomh.finance.domain.transaction.Category;
import com.artyomh.finance.domain.transaction.Transaction;
import com.artyomh.finance.domain.transaction.TransactionId;
import com.artyomh.finance.domain.transaction.TransactionKey;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.Set;

public final class ImportService {

    private final StatementParser parser;
    private final TransactionRepository transactions;
    private final ExchangeRateProvider rates;
    private final CategorizerFactory categorizers;

    public ImportService(StatementParser parser, TransactionRepository transactions,
                         ExchangeRateProvider rates, CategorizerFactory categorizers) {
        this.parser = parser;
        this.transactions = transactions;
        this.rates = rates;
        this.categorizers = categorizers;
    }

    public ImportSummary importFile(Path file) {
        ParseResult parsed = parser.parse(file);
        String sourceFile = file.getFileName().toString();
        Collection<Transaction> candidates = toTransactions(parsed.rows(), categorizers.create(), sourceFile);
        Set<TransactionId> inserted = transactions.saveNew(candidates);

        Collection<RowOutcome> outcomes = new ArrayList<>();
        for (RowProblem problem : parsed.problems()) {
            outcomes.add(new RowOutcome.Rejected(problem));
        }
        for (Transaction transaction : candidates) {
            outcomes.add(outcomeOf(transaction, inserted));
        }
        return summarize(sourceFile, outcomes, parsed.warnings());
    }

    private List<Transaction> toTransactions(SequencedCollection<ParsedRow> rows, Categorizer categorizer, String sourceFile) {
        Map<TransactionKey, Integer> occurrences = new HashMap<>();
        List<Transaction> result = new ArrayList<>(rows.size());
        for (ParsedRow row : rows) {
            // mask before hashing, so the id never depends on a full card number and stays stable
            String description = CardNumberMasker.mask(row.description());
            TransactionKey key = new TransactionKey(row.date(), description, row.direction(), row.money());
            int occurrence = occurrences.merge(key, 1, Integer::sum) - 1;
            ParsedRow masked =
                    new ParsedRow(row.lineNumber(), row.date(), description, row.direction(), row.money());
            result.add(toTransaction(masked, TransactionId.of(key, occurrence), categorizer, sourceFile));
        }
        return result;
    }

    private Transaction toTransaction(ParsedRow row, TransactionId id, Categorizer categorizer, String sourceFile) {
        String merchant = MerchantNameCleaner.clean(row.description());
        Category category = categorizer.categorize(
                new CategorizationInput(row.description(), merchant, row.direction()));
        BigDecimal rate = rateToAmd(row);
        return new Transaction(id, row.date(), row.description(), merchant, row.direction(), row.money(),
                row.money().amount().multiply(rate), rate, category, sourceFile);
    }

    private BigDecimal rateToAmd(ParsedRow row) {
        Money money = row.money();
        return switch (money.currency()) {
            case AMD -> BigDecimal.ONE;
            case USD -> rates.rateToAmd(money.currency(), row.date());
        };
    }

    private static RowOutcome outcomeOf(Transaction transaction, Collection<TransactionId> inserted) {
        if (inserted.contains(transaction.id())) {
            return new RowOutcome.Imported(transaction);
        }
        return new RowOutcome.Duplicate(transaction.id());
    }

    private static ImportSummary summarize(String sourceFile, Collection<RowOutcome> outcomes,
                                           Collection<FileWarning> warnings) {
        int added = 0;
        int duplicates = 0;
        Collection<RowProblem> rejected = new ArrayList<>();
        for (RowOutcome outcome : outcomes) {
            switch (outcome) {
                case RowOutcome.Imported _ -> added++;
                case RowOutcome.Duplicate _ -> duplicates++;
                case RowOutcome.Rejected rejection -> rejected.add(rejection.problem());
            }
        }
        return new ImportSummary(sourceFile, outcomes.size(), added, duplicates, rejected, warnings);
    }
}
