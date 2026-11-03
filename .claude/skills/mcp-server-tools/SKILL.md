---
name: mcp-server-tools
description: >-
  Invoke BEFORE writing or changing anything in mcp-server's MCP surface: an @McpTool /
  @McpToolParam method, the finance://categories @McpResource, a tool description, a tool result
  record, tool error mapping, import_statement path validation, server name/version/instructions,
  STDIO transport / logging / banner config, Claude Desktop registration, or MCP Inspector / SDK
  client contract tests. Covers the 9 tools (import_statement, list_months, spending_by_category,
  top_merchants, compare_months, find_subscriptions, unusual_transactions, search_transactions,
  set_category) and the tool design rules from the design deck. Spring AI 2.0.1 / MCP Java SDK 2.0.0.
  Triggers: "MCP", "tool", "@McpTool", "@McpResource", "tool description", "STDIO", "Claude Desktop",
  "MCP Inspector", "server instructions", "tool error", "spring.ai.mcp.server".
---

# MCP server & tools (Spring AI 2.0.1, STDIO)

**MCP is only the front door.** A tool method validates input, makes **one** application-service call
(Facade), and returns a small record. No business logic, no SQL, no formatting into prose — Claude writes
the explanation. Read `hexagonal-architecture` for placement; `spring-boot-testing` for the test pyramid.

## Verified facts (2026-11-03)

Checked against https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html,
`mcp-stdio-sse-server-boot-starter-docs.html`, `mcp-annotations-server.html`,
`mcp-annotations-special-params.html` **and** `javap` on the jars in `~/.m2`
(`spring-ai-mcp-annotations-2.0.1`, `spring-ai-autoconfigure-mcp-server-common-2.0.1`,
`io.modelcontextprotocol.sdk:mcp-core:2.0.0`). Docs site shows 2.0.2; our pom pins 2.0.1. Re-WebFetch
before editing Spring AI code (the `spring-ai-docs-reminder.sh` hook insists) — 1.x tutorials are wrong here.

| Fact | Value |
|---|---|
| Starter (STDIO / no web) | `org.springframework.ai:spring-ai-starter-mcp-server` — already in the pom, includes the annotations |
| Annotation package | `org.springframework.ai.mcp.annotation` (moved from `org.springaicommunity.mcp` in 2.0) |
| `@McpTool` attributes | `name`, `description`, `title`, `generateOutputSchema` (default `false`), `annotations`, `metaProvider` |
| `@McpTool.McpAnnotations` | `title`, `readOnlyHint` (default `false`), `destructiveHint` (`true`), `idempotentHint` (`false`), `openWorldHint` (`true`) |
| `@McpToolParam` attributes | `description`, `required` — **only these two** |
| `@McpResource` attributes | `uri` (supports `{var}` templates), `name`, `title`, `description`, `mimeType` (default `text/plain`), `annotations`, `metaProvider` |
| Discovery | Spring beans with annotated methods are auto-registered; `spring.ai.mcp.server.annotation-scanner.enabled` (default `true`) |
| Sync vs async | `spring.ai.mcp.server.type=SYNC` (default) registers only non-reactive methods — ours are all sync |
| Special params (hidden from schema) | `McpSyncRequestContext`, `McpMeta`, `CallToolRequest`, `@McpProgressToken String`, `McpTransportContext` |
| Return handling (bytecode of `AbstractMcpToolMethodCallback`) | `void` → text `"Done"`; `generateOutputSchema=false` → result serialized to JSON **text content**; `true` → `structuredContent` only + output schema; returning `CallToolResult` passes through |
| Exceptions | a `RuntimeException` becomes `CallToolResult.isError(true)` whose text is **`exception.getMessage()` + line separator + root-cause message**; a declared checked exception or `Error` fails the call; `McpError` propagates to the protocol |

**Properties** (`spring.ai.mcp.server.*`, from the 2.0.1 config metadata): `enabled`(true), `stdio`(false),
`name`(mcp-server), `version`(1.0.0), `instructions`(none), `type`(sync), `protocol`(streamable — HTTP
only, ignored for STDIO), `request-timeout`(20s), `capabilities.tool|resource|prompt|completion`(true),
`tool-change-notification`/`resource-change-notification`/`prompt-change-notification`(true),
`tool-response-mime-type.<tool>`, `expose-mcp-client-tools`(false), `annotation-scanner.enabled`(true).

Not verified — **verify against docs**: whether Claude Desktop renders `structuredContent`-only results
(so keep `generateOutputSchema=false` until checked); (`logging.console.enabled` is verified: it exists in Boot 4.1.2.)

## STDIO config (`mcp-server/src/main/resources/application.yaml`)

stdout **is** the JSON-RPC channel. One stray byte (banner, a log line, `System.out`) corrupts the stream
and Claude Desktop shows "server disconnected".

```yaml
spring:
  main:
    banner-mode: off
    web-application-type: none          # no Tomcat, no port
  ai:
    mcp:
      server:
        stdio: true
        name: finance-mcp
        version: @project.version@        # resource filtering; or hard-code and bump
        type: SYNC
        instructions: >
          Personal finance data from the user's own bank statements, stored locally.
          Amounts are numbers; totals are in AMD. Call list_months first when the user names no month.
          Months are YYYY-MM. Write the explanation yourself; tools return data only.
        capabilities:
          prompt: false
          completion: false
logging:
  console:
    enabled: false                      # verified in Boot 4.1.2 metadata; NOT `pattern.console: ""` —
                                        # logback prints an "Empty or null pattern" ERROR to stdout
  file:
    name: ${user.home}/.fin-track-mcp/logs/server.log
  level:
    root: WARN
    com.artyomh.finance: INFO           # never descriptions/merchants/amounts above DEBUG
```

Also: no `System.out`/`printStackTrace` in `src/main` (`stdio-hygiene-warn.sh` flags it); no dependency
that prints on startup; Flyway/HikariCP logs go to the file like everything else.

## The product surface

Tool names are `snake_case`. All month inputs are `String` `"YYYY-MM"`, parsed in the tool; outputs carry
`month` as `"YYYY-MM"` strings, amounts as `BigDecimal` (AMD unless the field says otherwise).

| Tool | Params | Result record (fields) | Hints |
|---|---|---|---|
| `import_statement` | `filePath` | `ImportResult(file, added, duplicates, rejected, List<RejectedRow(rowNumber, reason)>)` (rejections capped at 20 + `moreRejected`) | write, **idempotent**, not destructive |
| `list_months` | — | `List<MonthSummary(month, income, spent, net, transactionCount)>` | read-only |
| `spending_by_category` | `month` | `CategorySpending(month, income, spent, savingsRate, List<CategoryTotal(category, displayName, amount, share, count)>)` | read-only |
| `top_merchants` | `month`, `limit?` (default 10, max 50) | `List<MerchantTotal(merchant, category, amount, count)>` | read-only |
| `compare_months` | `monthA`, `monthB` | `MonthComparison(monthA, monthB, spentA, spentB, delta, List<CategoryDelta(category, amountA, amountB, delta)>)` | read-only |
| `find_subscriptions` | — | `List<Subscription(merchant, category, typicalAmount, currency, cadenceDays, lastCharged, yearlyCostAmd)>` | read-only |
| `unusual_transactions` | `month` | `List<UnusualTransaction(date, merchant, category, amountAmd, typicalAmountAmd, ratio)>` | read-only |
| `search_transactions` | `query?`, `month?`, `category?` | `SearchResult(List<TransactionView>, total, truncated)` — **max 50** rows | read-only |
| `set_category` | `merchant`, `category` | `CategoryChange(merchant, category, rowsUpdated)` | write, idempotent, not destructive |
| resource `finance://categories` | — | JSON list `{name, displayName, direction}` of the 16 categories | `application/json` |

MVP core: `spending_by_category`, `find_subscriptions`, `compare_months`. Result records live in
`application` (they are use-case outputs) unless the shape is MCP-only; no mirroring records.

## Tool design rules (deck slide 15)

1. **Question-shaped, not CRUD.** "Where did my money go in October?" → `spending_by_category`, then
   `compare_months`, then `unusual_transactions`. No `get_transactions_table`.
2. **Small structured records.** Pre-aggregated numbers, bounded lists. Never entities, never raw rows
   beyond the 50 cap, never the whole DB. Claude's context is the budget.
3. **Descriptions are product copy.** Claude picks tools only by name + description + param descriptions.
   Say what question it answers, units, format, defaults, limits, and when to call something else first.
   This is the one place `comment-style` brevity does not apply.
4. **Read-only by default.** Only `import_statement` and `set_category` write; mark the rest
   `readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false`.
5. **Idempotent writes.** Re-importing a file adds 0; repeating `set_category` updates the same rows.
6. **Errors list valid options.** `No data for 2026-03. Available: 2026-05 to 2026-10.` /
   `Unknown category 'FOOD'. Valid: GROCERIES, CAFES_RESTAURANTS, …` / `Month must be YYYY-MM, got '10/2026'.`
7. **Nothing leaves the machine.** `openWorldHint = false` everywhere; no network.

## Tool class shape

One class per cohesive group (`SpendingTools`, `ImportTools`, `CategoryTools`) in
`com.artyomh.finance.mcp.tool.<area>` (`package-layout`); Spring `@Component` is fine here (server module). Constructor injection.

```java
@Component
public class SpendingTools {

    private static final String MONTH_FORMAT = "Month in YYYY-MM format, e.g. 2026-10.";

    private final SpendingService spendingService;
    private final ToolErrors toolErrors;

    public SpendingTools(SpendingService spendingService, ToolErrors toolErrors) {
        this.spendingService = spendingService;
        this.toolErrors = toolErrors;
    }

    @McpTool(
            name = "spending_by_category",
            description = """
                    Totals for one month grouped by category: income, total spent, savings rate (0..1) and \
                    each expense category's amount in AMD, share of spending and transaction count. \
                    Use it for "where did my money go", "how much did I spend on X". \
                    Call list_months first if the user did not name a month.""",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CategorySpending spendingByCategory(
            @McpToolParam(description = MONTH_FORMAT, required = true) String month) {
        return toolErrors.call(() -> spendingService.byCategory(ToolInputs.month(month)));
    }
}
```

Optional params are boxed and defaulted without a ternary:

```java
int effectiveLimit = Optional.ofNullable(limit).orElse(properties.defaultMerchantLimit());
```

Validation of *shape* (month format, limit range, enum name) happens in the tool and throws the domain
`InvalidInputException` with the valid options; validation of *data* (month has no rows) happens in the
service, which throws `NoDataException` with the available range.

## Exception → tool error

Spring AI appends the **root cause's message** to the error text, so never rethrow with a cause and never let
a library exception reach the callback. One translator in `com.artyomh.finance.mcp.exception`:

```java
@Component
public class ToolErrors {

    private static final Logger LOG = LoggerFactory.getLogger(ToolErrors.class);
    private static final String INTERNAL = "Internal error in the finance server; details are in its log file.";

    public <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (FinanceException e) {
            throw new ToolFailure(e.getMessage());          // message is user copy, written by the thrower
        } catch (RuntimeException e) {
            LOG.error("Tool failed", e);                     // to the log file, never to stdout
            throw new ToolFailure(INTERNAL);
        }
    }
}

/** Cause-less so the MCP callback cannot append a library or PII message. */
final class ToolFailure extends RuntimeException {
    ToolFailure(String message) {
        super(message, null, false, false);
    }
}
```

Rules for `FinanceException` messages: actionable, list valid options, may echo the user's own input
(month, file name), **never** row contents, merchants from other rows, SQL, POI/CSV text or stack traces.
If a tool needs to switch over exception kinds, switch over the sealed `FinanceException` hierarchy
exhaustively (see `hexagonal-architecture` → exceptions).

## `import_statement` path validation (in the tool, before the service)

The model supplies the path, so treat it as untrusted:

1. Non-blank; `Path.of(filePath)` must be **absolute** (reject `~`, relative paths: "Give the absolute path,
   e.g. /Users/me/Downloads/statement.xlsx").
2. `toRealPath()` (resolves symlinks / `..`); `NoSuchFileException` → "File not found: <path>".
3. `Files.isRegularFile` and `Files.isReadable`.
4. Extension in `finance.import.allowed-extensions` (`xlsx`, `csv`) — the parser still checks headers.
5. `Files.size` ≤ `finance.import.max-file-bytes` (e.g. 10 MB) — zip-bomb / huge-CSV guard.
6. If `finance.import.allowed-roots` is set, the real path must `startsWith` one of them.

All limits come from an `@ConfigurationProperties` record (`ImportProperties`), not literals. Never return
file contents in an error.

## Resource `finance://categories`

```java
@McpResource(
        uri = "finance://categories",
        name = "categories",
        description = "The 16 fixed categories with display name and direction (INCOME/EXPENSE). "
                + "set_category and search_transactions accept only these names.",
        mimeType = "application/json")
public String categories() {
    return jsonMapper.writeValueAsString(categoryCatalog.all());   // Jackson 3 JsonMapper bean (Boot 4)
}
```

Return type `String` is the docs' shape; `ReadResourceResult` is also accepted. Keep `Category` the single
source — the resource is generated from the enum, never hand-written.

## Wiring

Domain/application classes have no Spring annotations; declare them in
`com.artyomh.finance.mcp.config.FinanceBeans` (`@Configuration`). Tools depend on application services
only — never on a repository or adapter. `@ConfigurationProperties` records (`ImportProperties`,
`FxProperties`, `DetectionProperties`, `StorageProperties`) live in `…mcp.config`, enabled with
`@ConfigurationPropertiesScan`.

## Testing

- **Unit** (no Spring): instantiate the tool with a stubbed service; assert input validation messages,
  defaulting (`limit` null → 10), cap (> 50 rejected with the range), and that a `RuntimeException`
  from the service becomes `ToolFailure(INTERNAL)` with no cause.
- **Description lint test**: reflect over `@McpTool` methods — every tool and every `@McpToolParam` has a
  non-blank description; names are `snake_case`; read-only tools carry `readOnlyHint = true`.
- **Integration** `@SpringBootTest` (STDIO off: `spring.ai.mcp.server.stdio=false`, temp SQLite): import a
  `mockdata` file, then call every tool bean.
- **Contract over STDIO** with the SDK client (verified signatures, mcp-core 2.0.0), after `package`:

```java
ServerParameters params = ServerParameters.builder("java")
        .args("-jar", jarPath.toString(), "--finance.storage.path=" + dbFile)
        .build();
McpSyncClient client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
        .requestTimeout(Duration.ofSeconds(20))
        .build();
client.initialize();
assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("spending_by_category");
CallToolResult result = client.callTool(new CallToolRequest("spending_by_category", Map.of("month", "2026-03")));
assertThat(result.isError()).isTrue();
client.closeGracefully();
```

- **Manually**: `./mvnw -pl mcp-server -am package` then
  `npx @modelcontextprotocol/inspector java -jar mcp-server/target/mcp-server-<v>.jar` —
  check tool list, descriptions, schemas, an error result, and that the server stays connected (a stdout
  leak shows as a parse error).

## Claude Desktop registration

`~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "finance": {
      "command": "/absolute/path/to/jdk-25/bin/java",
      "args": ["-jar", "/Users/<you>/Documents/fin-track-mcp/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar"]
    }
  }
}
```

Use absolute paths (Desktop does not inherit your shell `PATH`/`JAVA_HOME`). Restart Desktop after a rebuild.
Debug: `~/Library/Logs/Claude/mcp-server-finance.log` (Desktop side) + our log file (server side).

## Common mistakes

- 1.x names: `@Tool` / `ToolCallbackProvider` / `org.springaicommunity.mcp.annotation` — use `@McpTool` from
  `org.springframework.ai.mcp.annotation`.
- `spring.ai.mcp.server.protocol` set for STDIO (it's for HTTP); forgetting `stdio: true`.
- Logging, banner or `System.out` to stdout.
- Rethrowing with a cause → library/PII message appended to the tool error.
- Two service calls in one tool (orchestration belongs in the application service).
- Returning `Transaction` / unbounded lists; prose in results; formatting numbers ("1,067,201 AMD").
- A vague description ("Gets data") — Claude will not pick the tool, or will pick the wrong one.
- Forgetting `readOnlyHint`/`openWorldHint` (defaults say *destructive* and *open-world*).
