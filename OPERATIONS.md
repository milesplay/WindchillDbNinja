# Capture semantics, safe use and workload limits

This guide defines the agreed release behavior and its limits, not a promise of
complete Oracle history or production-scale performance. The qualified binary is
Windows x64 only; private evidence requires the reviewed local-NTFS ACL policy.

**Development and test environments only. Do not install or run DB Ninja in
production.** Use approved, access-controlled test data. A copy of production
data can still contain personal or confidential information and can still be too
large for a safe capture. There is no automatic production-environment detector.

## A normal investigation

1. Confirm an authorized development/test environment with its owner. Stop if
   the target is production or its classification is unknown.
2. Check monitoring scope, expected table sizes, the per-table cap and available undo.
3. In Quick Links choose **Ninja Trick**, confirm and optionally enter a description.
   Cancel must not mutate anything. Wait for authoritative running status.
4. Perform one narrow operation. Wait for it to complete/commit.
5. Choose **Ninja Stealth** as the starting username. Do not repeatedly submit Stop
   if its outcome is uncertain; inspect state, saved results and logs first.
6. The completed capture opens automatically. Read **Mode**, **Status**, **Warnings**
   and the diagnostic details before interpreting the changes.
7. Protect/export only approved data; delete only captures whose owner authorized it.

There is one server-wide capture. It can observe other users and background
changes, not just the administrator's operation. Native SQL/call-tree evidence
has a narrower, node-local request scope. There is no normal user Abort action.

### Reading the Ninja banner

The artwork stays the same from a submitted Ninja Trick request through the
capture window and Ninja Stealth collection, until authoritative state confirms
that the window has ended. A temporary status failure does not replace it with
a `CAPTURE STATUS UNKNOWN` headline or imply that capture has stopped.

Hover/focus the banner for the actual starting/running/collecting/unconfirmed
details. **The artwork alone is not confirmation that Start succeeded or that
the server is reachable.** Start/Stop submissions remain blocked when state is
unconfirmed. An uncertain Start/Stop outcome retains the artwork until a fresh
authoritative response resolves it.

Outside a confirmed/locally submitted capture window, an initial or idle status
outage does not display capture artwork. Instead it reports the error once per
distinct failure until recovery and retains the explanation on Quick Links.
After confirmed idle state, the banner is hidden. This is a presentation change,
not a change to database capture, timeouts or diagnostic limits.

## What Start and Stop do

**Start does not copy all business rows.** It records a start SCN, per-table
monitoring counters and the monitoring filter. A Start-time catalog is saved for
diagnostics. The release contract requires the same eligible physical table
scope for collection and diagnostics, frozen for that capture; a new table at
Stop must not silently enter it. If the in-memory baseline is lost after a
restart or cross-node operation, inspect the recovery warning rather than
assuming the original baseline has been reconstructed.

At Stop, the collector obtains the end SCN and narrows candidate tables using
monitoring counters/timestamps. Unavailable or unreliable activity evidence must
lead to a warning and conservative examination of eligible tables, not silently
suppress examination because stale counters are empty. Unsupported quoted/mixed-case
identifiers must be handled consistently by collection and diagnostics.
These are release requirements for corrected source and the selected binary;
[new acceptance evidence](LOCAL-INSTALL.md) must establish compliance. Do not
infer it from prior icon deployment, a green notice or an empty result.

| Mode | Actual mechanism | Limits |
|---|---|---|
| FLASHBACK | Anchored Oracle Version Query for committed row versions | Requires usable history/undo and privileges; results are reduced to net effects |
| SNAPSHOT comparison | At Stop, reads `startSCN - 1` and `endSCN` with `SELECT ... AS OF SCN` | Still depends on Oracle undo; it is not an independent backup |
| FLASHBACK+SNAPSHOT | Endpoint fallback and, where possible, ID-limited history recovery | Does not guarantee discovery of transient-only changes or complete intermediate history |

ORA-01555 is retried once at the same bounds. Persistent ORA-01555 or ORA-30052
may trigger endpoint comparison. This is not an unconditional workaround for
missing privileges, changed table definitions or arbitrary SQL errors.

When endpoint differences identify IDs, an ID-limited Version Query may recover
SCN/time/XID information if its operation/deltas exactly match the endpoint result.
This recovers **evidence**, not business data. A green recovered-result indicator
still does not mean every intermediate change was found.

## Strict endpoint NET, not an intermediate-event audit

The owner has selected **strict endpoint NET**. Compare a row's reportable state
at the capture endpoints, not its last intermediate committed image. FLASHBACK
and SNAPSHOT must agree on the resulting operation and old/new values when they
have sufficient equivalent data.

| Operation on a row | What to expect |
|---|---|
| INSERT then DELETE, absent at both endpoints | Net-zero: no row change |
| `A -> B -> delete`, present with A at the start | DELETE with old value **A**, not B |
| `A -> B / COMMIT -> A / COMMIT` | Net-zero: no reportable UPDATE, although Oracle may expose B |
| `A -> B -> C`, with C at the end | UPDATE from A to C, subject to reportable-column rules |
| `A -> B -> A` within one transaction, then one commit | B is not a separate committed version |
| Rollback, or commit after the Stop SCN | The change is outside this capture's committed result |

Earlier audited binaries disagreed on transient insert/delete and the old value
for update/delete. The chosen contract resolves that design decision; it does
not itself repair or deploy those binaries. Require current source/binary
regressions and separately approved live Oracle qualification. Never weaken the
strict NET expectations to make a test pass.

The reducer ignores `UPDATESTAMPA2`, `UPDATECOUNTA2` and `MODIFYSTAMPA2`, and
omits an UPDATE with no remaining reportable deltas. Changes to other rows,
versions or relationships may still appear.

SQL evidence may contain multiple update messages, but pre-execution messages
alone do not prove successful execution, commit or the exact row's value history.

## Not a business restore; not a wholly read-only tool

Normal capture reads do not issue business-table DROP/TRUNCATE, Flashback
Table/Database, observed-SQL replay, or restoration of old business values.
The tool does not tell other business transactions to roll back.

It does, however:

- Persist/update/delete its own `DBCAPTURE*` records and private evidence.
- Lock its own session table/row for Start/Stop concurrency.
- Call `DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO`.
- Use the current Windchill MethodContext-managed database connection.

Start, the user's business operation and Stop are separate normal UI requests,
not one long business transaction. Embedding the service inside a different
business transaction requires a separate review. General DBMS_STATS transaction
caveats must not be dismissed merely because this code has no explicit SQL
COMMIT/ROLLBACK at a particular call site.

## Stored result caps are not database-work budgets

| Setting / behavior | What it does **not** bound |
|---|---|
| Default 5,000 accepted changed IDs/net rows per table | All scan rows, all intermediate versions, all tables together, total heap or saved size |
| Snapshot JDBC fetch size 100 | Total rows read |
| Streaming ordered endpoint comparison | All accumulated detected changes across the capture |
| Native evidence: 10 minutes / 2,000 statements / 8 MiB | Database comparison SQL runtime or I/O |
| Browser Stop wait: 180 seconds | Guaranteed Oracle cancellation or query execution limit |

A million-row table with one changed row may require two million-row endpoint
reads, despite a small saved difference. This is a logical-work example, not a
claim of exactly two million physical I/Os.

The history/comparison collectors do not explicitly set a query timeout or SQL
row limit. CPU, I/O, undo reconstruction, sorting/TEMP and accumulated changes can
be significant. Functional tests are not a production-sized load test.

Keep captures short and narrow. **Currently hidden is display-only** and cannot
reduce database workload. The monitoring-scope dual list controls eligible
default-exclusion opt-ins, not a universal editor for every business table.
Review site exclusions separately when large tables must be excluded.

## Evidence and representation limits

- Eligible native DML is captured before JDBC execution from completed,
  authenticated AJP requests on the starting MethodServer.
- SELECT, background work, failed/unfinished requests and unsupported forms are
  excluded. Cluster-wide coverage and exact PBO-row attribution are not promised.
- Request and MethodContext identity, not a reused thread alone, group call trees.
- Missing bind values and historical evidence are not reconstructed or invented.
- Framework-hidden frames remain in the full recorded stack; truncation and caps
  are explicit. Global logger levels, appenders and profiler adapter maps are not replaced.
- CLOB/NCLOB comparison uses a bounded prefix (currently 1,000 characters and an
  omission marker); binary values are represented by length. Same-representation
  content changes can be missed. This is not full LOB auditing.
- Current-object inspection is separate from saved history. An unavailable current
  row does not authorize rewriting the capture.

## Data protection

Results may contain values a user would not otherwise be entitled to view.
Keep the site-administrator gate and validate it after upgrades. SQL, stack traces,
object references, descriptions, schema names and CSV exports can all be sensitive.

Private runtime evidence is under the Windchill-owned `.dbcapture-evidence`
directory; use its owner-only protections and include it in approved operational
backups. It must never become a web asset or a public source artifact.
Display filters do not delete saved data. Deleting a capture removes its own
records/evidence, not the investigated business objects.
