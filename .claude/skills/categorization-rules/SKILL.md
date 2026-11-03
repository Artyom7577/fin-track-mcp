---
name: categorization-rules
description: >-
  Invoke BEFORE writing or changing how transactions get a category or are analysed in fin-track-mcp:
  the CategorizationRule chain (OverrideRule → MerchantRule → KeywordRule → fallback), keyword and
  merchant lists, whole-word matching and text normalization, Category/Direction consistency,
  set_category and the category_overrides table, re-categorizing existing rows, subscription detection
  (find_subscriptions) and unusual-transaction detection (unusual_transactions) with their thresholds.
  Triggers: "category", "categorize", "categorization", "rule", "keyword", "merchant rule", "override",
  "set_category", "OTHER", "miscategorized", "wrong category", "whole word", "ENA", "subscription",
  "recurring", "unusual", "anomaly", "outlier", "threshold", "Chain of Responsibility".
---

# Categorization & detection rules

All of this is **pure domain logic** in `domain` (no Spring, no SQL), table-tested in milliseconds.
Tunables come in as plain records built by the server from `@ConfigurationProperties`. Categories are the fixed
16-value `Category` enum (each with `displayName` and `Direction`) — never invent one; users fix mistakes per
merchant with `set_category`.

## Chain of Responsibility (deck slide 14)

```java
public interface CategorizationRule {
    /** The category this rule is sure of, or empty to defer to the next rule. */
    Optional<Category> apply(Transaction tx);
}
```

Order = priority; first non-empty answer wins:

| # | Rule | Answers when | Source |
|---|---|---|---|
| 1 | `OverrideRule` | the user set a category for this merchant | `category_overrides` (via a domain-facing lookup port) |
| 2 | `MerchantRule` | normalized merchant is a known exact name (`YEREVAN CITY` → GROCERIES) | config |
| 3 | `KeywordRule` | a keyword occurs as a **whole word** (`SUPERMARKET` → GROCERIES) | config |
| 4 | `FallbackRule` | always | `OTHER` for EXPENSE, `OTHER_INCOME` for INCOME |

```java
public final class CategorizationChain {

    private final List<CategorizationRule> rules;

    public CategorizationChain(List<CategorizationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public Category categorize(Transaction tx) {
        for (CategorizationRule rule : rules) {
            Optional<Category> answer = rule.apply(tx).filter(category -> category.direction() == tx.direction());
            if (answer.isPresent()) {
                return answer.get();
            }
        }
        throw new IllegalStateException("Chain must end with FallbackRule");
    }
}
```

- **Direction consistency is enforced once, in the chain:** an answer whose `Category.direction()` differs from
  the transaction's is treated as empty ("ask the next rule"). An INCOME refund from `SAS SUPERMARKET` never
  becomes GROCERIES; it falls through to `OTHER_INCOME`.
- The transaction is built with its direction's fallback category, then `tx.withCategory(chain.categorize(tx))`;
  rules read only `merchant`, `description`, `direction`, `money` — never the incoming `category`.
- Adding an ML rule later = one more element in the list (server wiring); nothing else changes.

## Whole-word matching

"ENA" (Electric Networks of Armenia → UTILITIES) must not match "OPENAI". Plain `contains` is banned.

**Normalize** once per transaction (private method in the rule; the same function normalizes keywords at
construction): `Normalizer.normalize(text, NFKC)` → `toUpperCase(Locale.ROOT)` → every char that is not a letter
or digit becomes a space → collapse spaces → trim.

**Match** with letter/digit lookarounds (Unicode-aware; Java's `\b` is ASCII-ish unless
`UNICODE_CHARACTER_CLASS`, and Armenian/Cyrillic must work):

```java
private static Pattern wholeWord(String normalizedKeyword) {
    return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizedKeyword) + "(?![\\p{L}\\p{N}])");
}
```

- Precompile all patterns in the constructor; keep `(Pattern, Category)` pairs sorted **longest keyword first**
  so `BOLT FOOD` (CAFES_RESTAURANTS) beats `BOLT` (TRANSPORT). Equal length → config order.
- Multi-word keywords work because normalization collapses separators (`BOLT*FOOD` → `BOLT FOOD`).
- Startup validation (in the server's properties record): a keyword listed under two categories, a blank keyword,
  or an unknown category name fails fast with the offending entry.

**Transliteration:** descriptions may be Latin (`APTEKA`), Cyrillic (`АПТЕКА`) or Armenian (`ԴԵՂԱՏՈՒՆ`). MVP: list
each spelling as its own keyword — explicit and testable. Automatic transliteration is lossy and ambiguous; if
added later it is one more normalization step behind the same rule API (grill first).

Config sketch (server module, bound to a record, converted to domain maps):

```yaml
finance:
  categorization:
    merchants:
      YEREVAN CITY: GROCERIES
      OPENAI: SUBSCRIPTIONS
    keywords:
      GROCERIES: [SUPERMARKET, YEREVAN CITY, SAS]
      UTILITIES: [ENA, VEOLIA, GAZPROM]
      CAFES_RESTAURANTS: [CAFE, COFFEE, BOLT FOOD]
      TRANSPORT: [BOLT, YANDEX GO, GG TAXI]
```

## set_category (overrides)

`set_category(merchant, category)` → `CategoryService.setCategory(MerchantCategory)`:

1. `category` must be a `Category` name (case-insensitive) → else `InvalidInputException` listing all 16.
2. `merchant` normalized the same way as at import; unknown → `NotFoundException` with up to 5 known merchants
   containing the input ("Unknown merchant 'SAS'. Did you mean: SAS SUPERMARKET, SAS GOURMET?").
3. Direction: if the merchant has no rows whose direction equals `category.direction()` →
   `InvalidInputException("SALARY is an income category; SAS SUPERMARKET has only expenses. Expense categories: …")`.
4. In **one** repository transaction: upsert `category_overrides(merchant, category)` and
   `UPDATE transactions SET category = :category WHERE merchant = :merchant AND direction = :direction`.
5. Return `CategoryChange(merchant, category, rowsUpdated)`. Idempotent: repeating it updates the same rows.

Future imports pick the override up through `OverrideRule`. Overrides are per normalized merchant — never per
transaction id, never a new category.

## Subscription detection (`find_subscriptions`)

`SubscriptionDetector.detect(Collection<Transaction> expenses, LocalDate asOf, SubscriptionThresholds t)`:

1. Group EXPENSE transactions by merchant (and currency — a USD subscription is compared in USD).
2. Keep groups with `≥ t.minOccurrences()` (3).
3. Amount stability: every charge within `t.amountTolerance()` (0.10 = ±10%) of the group **median**
   (`compareTo`, `BigDecimal` math, HALF_EVEN).
4. Cadence: intervals between consecutive dates; median interval in `[t.minCadenceDays(), t.maxCadenceDays()]`
   (25..35 for monthly) and no interval deviating more than `t.cadenceToleranceDays()` from the median
   (one missed month may be allowed by config, not by accident).
5. Active: last charge within `t.maxDaysSinceLast()` (45) of `asOf` — `asOf` = latest transaction date in the
   data, passed in; **no `LocalDate.now()` in the domain**.
6. Result per merchant: median amount, currency, cadence, last charged, `yearlyCostAmd = medianAmountAmd × 12`.
   Sort by yearly cost desc.

## Unusual-transaction detection (`unusual_transactions`)

`UnusualSpendDetector.detect(Collection<Transaction> month, Collection<Transaction> history, UnusualThresholds t)`:

- Baseline per transaction = **median** `amountAmd` of the same merchant in `history` (the
  `t.lookbackMonths()` before the month, excluding the transaction itself) when it has `≥ t.minSamples()`;
  otherwise the median of its **category**; neither → skip (no basis to call it unusual).
- Flag when `amountAmd ≥ t.ratio() × baseline` (3.0) **and** `amountAmd ≥ t.minAmountAmd()` (20,000 AMD — ignores
  a 3× coffee).
- Return `UnusualTransaction(date, merchant, category, amountAmd, typicalAmountAmd, ratio)`, ratio at scale 2,
  sorted by ratio desc, capped at `t.maxResults()`.
- Median, not mean: one past outlier must not hide the next one.

All numbers above are **defaults in `application.yaml`** bound to `@ConfigurationProperties` records
(`DetectionProperties`) → mapped to `SubscriptionThresholds` / `UnusualThresholds` domain records. No literals
in the detectors.

## Tests (table-driven, no Spring)

```java
@ParameterizedTest
@CsvSource({
        "POS 4578****1234 SAS SUPERMARKET,  EXPENSE, GROCERIES",
        "ENA ELECTRICITY PAYMENT,           EXPENSE, UTILITIES",
        "OPENAI *CHATGPT SUBSCR,            EXPENSE, SUBSCRIPTIONS",   // must not hit ENA
        "SCENARIO STUDIO,                   EXPENSE, OTHER",           // ENA inside a word
        "BOLT FOOD,                         EXPENSE, CAFES_RESTAURANTS",
        "BOLT,                              EXPENSE, TRANSPORT",
        "SAS SUPERMARKET REFUND,            INCOME,  OTHER_INCOME",    // direction guard
})
void categorizes(String description, Direction direction, Category expected) { … }
```

Also: override beats merchant beats keyword; empty chain answer impossible (fallback); detector tables for
exactly-at-threshold amounts/cadences (inclusive bounds stated in the test name), 2 vs 3 occurrences, a skipped
month, a stale subscription, merchant vs category baseline, the `minAmountAmd` floor. Use `mockdata`
for an end-to-end "known subscriptions are found" check.

## Common mistakes

- `description.contains(keyword)` or `\b` without Unicode handling.
- Lowercasing with the default locale; normalizing keywords and text differently.
- A rule returning a category of the wrong direction and nobody guarding it.
- `set_category` creating categories, updating by transaction id, or not touching existing rows.
- Mean instead of median; `LocalDate.now()` in detectors; thresholds as literals.
- Detectors reading the repository — services fetch, detectors compute.
