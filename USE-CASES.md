# From Windchill operations to database understanding

DB Ninja was created to help Windchill developers answer:

> Which database entries changed when I performed this operation, and how can
> that evidence improve my QML report, customization or troubleshooting?

Here **QML means Windchill Query Builder's Query Markup Language**, not Qt QML.
The goal is to connect observable behavior to the data model, rather than guess
table names or treat one captured SQL statement as a supported programming interface.

**Development and test environments only. Do not install or run DB Ninja in
production.** All recipes below require an authorized test context; sanitize
any copied business data and shared evidence.
The first binary is restricted to the qualified Windows x64/Oracle 19c baseline
in [COMPATIBILITY.md](COMPATIBILITY.md); other filesystems and CPS levels require
separate qualification.
Complete the release and target-acceptance gates in
[INSTALL.md](INSTALL.md) before using a new source/binary candidate. Prior icon
deployment does not establish that the new collector fixes are deployed.

## What is available now?

| Question | Current capability | Interpretation limit |
|---|---|---|
| Which tables changed in a short operation window? | Saved capture/table/operation results | Other users/background work can contribute to the same window |
| Which entries changed? | Recorded row IDs/object references and grouped change results | Only eligible tables/rows and available history are included |
| What values changed? | Saved column deltas and supported current-object comparison | Net effects, noise exclusions, LOB representation and row caps apply |
| Were entries created, updated, deleted or logically deleted? | Operation classification and saved differences | Not a complete transaction/audit reconstruction |
| What native DML/call path was observed? | Bounded SQL and synchronous call trees for eligible completed AJP requests | Local to the starting node; pre-execution evidence, not proof of commit |
| Can I investigate table relationships? | Compare changed IDs, reference values, object types and timing | Manual hypothesis/testing; no automatic foreign-key or ER-graph discovery |
| Can I use findings in a QML report? | Identify candidate tables, fields, filters and JOIN hypotheses | No QML generator, validator or guarantee of report correctness |
| Can I share/review a focused result? | Saved descriptions/search and visible-grid CSV | Review/redact sensitive values before sharing |

Read [OPERATIONS.md](OPERATIONS.md) for the detailed fidelity, security and workload limits.
The agreed **strict endpoint NET** contract omits insert-then-delete and
update-then-revert net-zero changes; `A -> B -> delete` reports old value `A`.
These are endpoint results, not a full sequence of intermediate events.

## 1. Understand relationships for a QML report

**Example goal:** report a business object's attributes together with related
objects, without accidentally duplicating rows or selecting the wrong revision.

1. In a disposable test context, choose one known object and one controlled
   operation: create it, change an attribute, or add/remove one relationship.
2. Give the capture a description naming the question, not just "test".
3. Observe which table entries and reference-like values changed.
4. Use the saved IDs and object references to investigate candidate relationships.
   Separate master, version/iteration and link concepts using the supported
   Windchill object model and the target release's documentation.
5. Build a small Query Builder/QML query that tests the proposed JOIN/filter.
6. Test multiple relationships, empty relationships, older revisions/iterations,
   lifecycle states and duplicate rows; validate normal access-control behavior.

**Useful outcome:** a better-supported hypothesis about the tables and values
your report needs, plus a repeatable scenario for checking it.

**Not a valid conclusion:** "These two tables changed together, so their similarly
named IDs must be a JOIN." A captured change cannot prove relationship cardinality,
all unmodified references, latest-version semantics or a complete reporting schema.

## 2. Understand standard behavior before customizing it

Capture a standard operation in an unmodified/test environment, such as an
attribute update, check-in, revise or addition of a relationship. Examine the
visible change groups and, if available, native SQL/call-tree evidence.

Then repeat an equivalent controlled scenario with your customization enabled.
Use separate, clearly named captures. Inspect differences manually or export
the relevant grids for an approved local comparison.

This can reveal unexpected side effects, related entries you did not anticipate,
or an extension that ran at a different point than expected. It does not turn
internal SQL or a stack frame into a supported API. Implement changes through
supported Windchill APIs; do not replay captured DML against business tables.

## 3. Troubleshoot "the UI changed, but the report is wrong"

Use a known object and a narrow capture to separate questions:

- Did the expected persisted entry/column change within this committed window?
- Was a different version, iteration or relationship entry involved?
- Is a result absent because of report filters, display-only hidden tables,
  capture scope, a cap or unavailable undo?
- Does the native request evidence suggest which issuing path to inspect next?

Follow up with the report definition, application logs, object-model documentation
and the appropriate DBA diagnostics. An absent capture result is **not proof that
no write occurred**: net-zero changes, unsupported tables, timing, undo and
representation limits must be considered first.

## 4. Review a customization regression

Keep a repeatable test scenario and capture its relevant behavior before and
after a code/configuration change. Compare:

- Expected versus unexpected table/operation groups.
- Reference values and changed business columns.
- Created/deleted/logically deleted entries.
- Warnings and evidence completeness, not just a green status.

This is a useful supplement to unit/integration tests and business assertions.
There is no built-in cross-capture diff engine or automated regression verdict.
Normalize differing test IDs/timestamps when comparing exports, and independently
verify the business outcome.

## 5. Investigate integrations, imports and custom endpoints

For an approved test request/import, a short capture can show its **database
effects**, including related changes that the client did not directly request.
This helps validate mappings, understand lifecycle side effects and formulate
targeted troubleshooting questions.

The database window is broad, but native SQL/call-tree evidence excludes
background and failed/unfinished requests and is node-local. A job may therefore
have row changes without corresponding native request evidence. Do not market
this as an integration traffic recorder or queue/background profiler.

## 6. Teach the persistence model and document findings

Use a small, repeatable exercise to connect a UI operation to changed records:

1. State a question, such as "What changes when one relationship is removed?"
2. Predict affected object types/tables from the documentation.
3. Capture the controlled operation and review actual evidence.
4. Record what matched, what did not and what still needs independent confirmation.
5. Build a sanitized explanation or diagram manually, retaining the limitations.

This is useful for onboarding customization/report developers and technical
handoffs. Use synthetic data; an internal capture is not automatically safe for
a public training slide or GitHub issue.

## 7. Narrow the starting point for a DBA investigation

Affected tables, the saved capture window, representative changed entries and
explicit history warnings can make a DBA/application investigation more focused.
The native SQL/call tree may offer additional context for eligible requests.

DB Ninja does not supply explain plans, a DB-wide SQL trace, query timings,
performance root-cause analysis or undo-repair recommendations. It can help ask
a better question; it is not a replacement for Oracle/application diagnostics.

## A short first exercise

In a qualified non-production environment:

1. Obtain permission to use one disposable object and to create a capture.
2. Record the object's known initial value using normal Windchill UI/API access.
3. Start a described capture, perform **one** change, wait for completion/commit,
   then stop.
4. Find the known entry/value in the saved results. Inspect warnings, scope and
   any native evidence before interpreting unrelated rows.
5. Validate the hypothesis in Query Builder or a supported API query.
6. Restore/delete only your authorized test objects and captures.

Do not use elapsed time as an acceptance guarantee: a short capture can still
trigger expensive reads on a large table.
For release qualification, separately authorize disposable insert/delete and
update/delete scenarios and compare FLASHBACK with SNAPSHOT results. A mocked
test is not evidence of actual Oracle commits, undo availability or user access.

## Explicitly outside the current product

- Automatic ER diagrams, foreign-key discovery, JOIN/cardinality inference.
- Automatic QML/query generation or validation.
- Data repair, SQL replay, rollback or backup/restore.
- Complete audit/compliance records or exact per-user/per-request row attribution.
- DB-wide performance tracing or automatic CPS/schema compatibility certification.
- Non-Oracle collectors.

These boundaries keep the useful promise precise: **observe changes, form better
hypotheses, and verify them with the supported Windchill tools and data model**.
