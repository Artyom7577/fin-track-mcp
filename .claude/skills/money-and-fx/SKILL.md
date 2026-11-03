---
name: money-and-fx
description: >-
  Invoke BEFORE touching money, amounts, currency, rounding, totals, minor units, exchange rates or
  USD→AMD conversion in fin-track-mcp: the Money record, Currency/Direction enums, BigDecimal
  scale and RoundingMode, amountAmd + exchangeRate on Transaction, minor-unit conversion in the
  SQLite repository, the ExchangeRateProvider port (FixedRateProvider from @ConfigurationProperties,
  later CbaRateProvider + per-date cache), aggregate totals and savings rate. Triggers: "Money",
  "BigDecimal", "amount", "currency", "AMD", "USD", "dram", "rounding", "scale", "minor units",
  "exchange rate", "FX", "convert", "total", "sum", "savings rate", "CBA".
---

# Money & FX

The two bugs this skill prevents: **adding USD to AMD**, and **drifting pennies** from `double` or
inconsistent rounding. Everything else follows from the `Money` value object.

## Decisions (documented here; change here first)

| Question | Decision | Why |
|---|---|---|
| Java type | `BigDecimal` only; never `double`/`float`/`long` for amounts in domain | exact decimal |
| Sign | `Money.amount` is **> 0**; `Direction` (`EXPENSE`/`INCOME`) says in/out | no sign bugs in sums/filters |
| Currencies | `enum Currency { AMD(2), USD(2) }` with `minorDigits()` | closed set → exhaustive switch |
| AMD minor digits | **2**, per ISO 4217 (`java.util.Currency.getInstance("AMD").getDefaultFractionDigits()` = 2) even though drams are shown whole | one rule for every currency; banks do send `1500.00`; storage stays lossless if luma ever appear |
| Scale in domain | `Money` normalizes to `currency.minorDigits()` with `setScale(minorDigits, HALF_EVEN)` **only for exact input**; rejects inputs with more digits (a statement saying `9.999 USD` is a `Rejected` row) | parse errors aren't silently rounded |
| Rounding mode | `RoundingMode.HALF_EVEN` for every computed rounding (conversion, shares) | unbiased over many rows |
| Storage | integer **minor units** in SQLite (`9.99 USD → 999`, `1500 AMD → 150000`) | exact, fast `SUM` |
| Rate storage | `exchange_rate` as `TEXT` (`BigDecimal.toPlainString()`) | exact audit; not money, so not minor units |
| Totals | always in **AMD**, summed from `amountAmd` | one-currency sums |
| Formatting | **none on the server** — tools return `BigDecimal`; Claude writes "1,067,201 AMD" | server never writes prose |

## Types (`domain`)

```java
public enum Currency {
    AMD(2),
    USD(2);

    private final int minorDigits;

    Currency(int minorDigits) {
        this.minorDigits = minorDigits;
    }

    public int minorDigits() {
        return minorDigits;
    }
}

public enum Direction { EXPENSE, INCOME }

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new InvalidInputException("Amount must be positive, got " + amount.toPlainString());
        }
        if (amount.stripTrailingZeros().scale() > currency.minorDigits()) {
            throw new InvalidInputException("Too many decimals for " + currency + ": " + amount.toPlainString());
        }
        amount = amount.setScale(currency.minorDigits(), RoundingMode.UNNECESSARY);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public long toMinorUnits() {
        return amount.movePointRight(currency.minorDigits()).longValueExact();
    }

    public static Money ofMinorUnits(long minorUnits, Currency currency) {
        return new Money(BigDecimal.valueOf(minorUnits, currency.minorDigits()), currency);
    }

    private void requireSameCurrency(Money other) {
        if (currency != other.currency) {
            throw new InvalidInputException("Cannot combine " + currency + " with " + other.currency);
        }
    }
}
```

- No `minus` that can go negative — differences between months are plain `BigDecimal` deltas in AMD
  (a delta can be negative; `Money` cannot).
- `equals`: records compare `BigDecimal` with scale, which is why the constructor normalizes scale.
  Elsewhere compare amounts with `compareTo`, never `equals`.
- Zero is not `Money`; an "empty month total" is `BigDecimal.ZERO.setScale(2)` in AMD.

## Transaction keeps the fact and the conversion

`Transaction(id, date, description, merchant, direction, money, amountAmd, exchangeRate, category, sourceFile)`:

- `money` — what the bank said (`9.99 USD`). Never rewritten.
- `exchangeRate` — AMD per 1 unit of `money.currency()` used at import (`1` for AMD).
- `amountAmd` — `money.amount × exchangeRate`, `setScale(AMD.minorDigits(), HALF_EVEN)`.

Recording both lets totals be a single `SUM(amount_amd_minor)` and lets a later `CbaRateProvider` re-price
history (a recalculation command, not an import side effect).

## ExchangeRateProvider (Strategy, port in `application`)

```java
public interface ExchangeRateProvider {
    /** AMD per one unit of {@code from} on {@code date}. */
    BigDecimal amdPerUnit(Currency from, LocalDate date);
}
```

Conversion is application logic (one place, used by `ImportService`):

```java
BigDecimal rate = switch (money.currency()) {
    case AMD -> BigDecimal.ONE;
    case USD -> rates.amdPerUnit(Currency.USD, date);
};
BigDecimal amountAmd = money.amount().multiply(rate).setScale(Currency.AMD.minorDigits(), RoundingMode.HALF_EVEN);
```

Exhaustive `switch`, no `default`: adding `EUR` to the enum breaks compilation here — intended.

**MVP — `FixedRateProvider`** (`adapter-fx`), rate from config, validated at startup:

```java
@ConfigurationProperties("finance.fx")
@Validated
public record FxProperties(@NotNull @Positive BigDecimal usdAmd) { }      // server module
```

```yaml
finance:
  fx:
    usd-amd: 385.00
```

The server builds `new FixedRateProvider(Map.of(Currency.USD, props.usdAmd()))`; the adapter itself has no
Spring. Unknown currency → `UnsupportedCurrencyException` listing supported ones.

**Later — `CbaRateProvider` + cache:** fetches the Central Bank of Armenia daily rate; cache keyed by
`(currency, date)` (rates are immutable once published, so no TTL; persist them in an `fx_rates` table so
re-imports never call out). Weekends/holidays → the latest earlier published rate. Wrap it as a decorator
(`CachingRateProvider implements ExchangeRateProvider`) so the import code does not change. Network access
breaks the MVP "nothing leaves the machine" rule — grill the user before building it.

## Repository boundary (minor units)

`adapter-persistence` converts both ways and nothing else does:

```java
.param("amount_minor", tx.money().toMinorUnits())
.param("currency", tx.money().currency().name())
.param("amount_amd_minor", tx.amountAmd().movePointRight(Currency.AMD.minorDigits()).longValueExact())
.param("exchange_rate", tx.exchangeRate().toPlainString())
```

Reading: `Money.ofMinorUnits(rs.getLong("amount_minor"), Currency.valueOf(rs.getString("currency")))`,
`BigDecimal.valueOf(rs.getLong("amount_amd_minor"), Currency.AMD.minorDigits())`,
`new BigDecimal(rs.getString("exchange_rate"))`. Aggregate in SQL on `amount_amd_minor` (integer `SUM`),
convert once at the end. See `sqlite-jdbcclient`.

## Totals, shares, rates

- Sum `amountAmd` grouped by `direction` / `category`. Never sum `money.amount()` across rows unless all share
  a currency (then use `Money.plus`).
- `share = categoryTotal / spent` → `divide(spent, 4, HALF_EVEN)`; `savingsRate = (income - spent) / income`
  with the same scale; income zero → `savingsRate` `null`-free: return `Optional`/omit, never divide by zero.
- Name the scale constants (`RATIO_SCALE = 4`); no magic numbers.

## Tests (table-driven, no Spring)

| Case | Expect |
|---|---|
| `new Money(new BigDecimal("9.99"), USD).toMinorUnits()` | `999` |
| `new Money(new BigDecimal("1500"), AMD).amount()` | `1500.00` |
| `new Money(new BigDecimal("9.999"), USD)` | `InvalidInputException` |
| `new Money(ZERO, AMD)` / negative | `InvalidInputException` |
| `usd(10).plus(amd(10))` | `InvalidInputException` |
| `9.99 USD × 385.00` | `amountAmd = 3846.15` |
| HALF_EVEN tie `0.125 → 0.12`, `0.135 → 0.14` | as stated |
| minor-unit round trip for every `Currency` | identity |

## Common mistakes

- `new BigDecimal(0.1)` (double ctor) — use `new BigDecimal("0.1")` / `BigDecimal.valueOf(long, scale)`.
- `bd1.equals(bd2)` for amounts — use `compareTo`.
- `setScale(2)` without a rounding mode where rounding can occur (throws or, worse, someone adds `HALF_UP`).
- Storing signed amounts, or storing `REAL` in SQLite.
- Formatting with thousands separators / currency symbols in a tool result.
- Converting at query time with today's rate — conversion happens once, at import, and is recorded.
