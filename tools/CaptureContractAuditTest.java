package com.custom.dbcapture.engine;

import com.custom.dbcapture.DbCaptureAuthorization;
import com.custom.dbcapture.DbCaptureObjectReader;
import com.custom.dbcapture.DbCaptureSession;
import com.custom.dbcapture.diagnostics.SessionEvidenceStore;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import wt.util.WTProperties;

/**
 * Offline publication audit. Every JDBC object is a strict in-memory proxy; no
 * driver, MethodContext, persistence manager, live logging or service is called.
 * Failures are accumulated so unresolved behavioral contracts remain visible.
 */
public final class CaptureContractAuditTest {
   private static int passed;
   private static final List<String> failures = new ArrayList<>();
   private static final Timestamp WHEN = Timestamp.valueOf("2026-09-20 12:00:00");
   private static final ChangeReducer REDUCER = new ChangeReducer(new IdentityResolver() {
      @Override
      public String resolve(String className, long id, Map<String, String> columns) {
         return "synthetic:" + id;
      }
   });

   public static void main(String[] args) throws Exception {
      if (args.length != 1) throw new IllegalArgumentException("Supply an unused project-local fixture-home path.");
      Path fixture = Path.of(args[0]).toAbsolutePath().normalize();
      if (java.nio.file.Files.exists(fixture)) {
         throw new IllegalArgumentException("Fixture home must not exist; this test never writes settings.");
      }
      Properties configuration = WTProperties.getLocalProperties();
      Object oldHome = configuration.get("wt.home");
      Object oldCap = configuration.get("com.custom.dbcapture.maxRowsPerTable");
      Object oldExcludes = configuration.get(TableFilter.EXCLUDE_PROPERTY);
      try {
         configuration.setProperty("wt.home", fixture.toString());
         configuration.setProperty("com.custom.dbcapture.maxRowsPerTable", "5000");
         configuration.setProperty(TableFilter.EXCLUDE_PROPERTY, "");
         reducers();
         snapshots();
         flashback();
         identifierBounds();
         locks();
         engineRecovery();
         engineScope();
         activityFallbacks();
         objectReads();
         identities();
      } finally {
         restore(configuration, "wt.home", oldHome);
         restore(configuration, "com.custom.dbcapture.maxRowsPerTable", oldCap);
         restore(configuration, TableFilter.EXCLUDE_PROPERTY, oldExcludes);
      }
      System.out.println("CaptureContractAuditTest: " + passed + " passed, " + failures.size()
            + " failed contract assertions (strict JDBC proxies; no database/server writes).");
      for (String failure : failures) System.out.println("FAIL: " + failure);
      if (!failures.isEmpty()) throw new AssertionError(failures.size() + " unresolved capture contracts");
   }

   private static void reducers() throws Exception {
      RowVersion before = version(1, null, 0, "A", "0", "1");
      RowVersion update = version(1, "U", 120, "B", "0", "2");
      RowVersion deleted = version(1, "D", 130, "B", "0", "2");
      check(REDUCER.reduce("WTPART", List.of(before)).isEmpty(), "untouched baseline produces no change");
      CapturedChange created = only(REDUCER.reduce("WTPART", List.of(version(1, "I", 110, "A", "0", "1"))));
      check("CREATE".equals(created.getOperation()), "insert classifies as CREATE");
      check(delta(created, "NAME", null, "A") && created.getDeltas().stream()
            .noneMatch(d -> d.getColumn().equals("UPDATECOUNTA2")), "CREATE retains values, omits noise");
      CapturedChange changed = only(REDUCER.reduce("WTPART", List.of(before, update)));
      check("UPDATE".equals(changed.getOperation()) && delta(changed, "NAME", "A", "B"),
            "committed UPDATE preserves baseline and endpoint values");
      check(changed.getChangeScn() == 120 && WHEN.equals(changed.getChangeTime())
            && "ABC".equals(changed.getTransactionId()), "last committed metadata survives reduction");
      check(REDUCER.reduce("WTPART", List.of(before, update, version(1, "U", 130, "A", "0", "3")))
            .isEmpty(), "committed A-B-A revert is net zero");
      check(REDUCER.reduce("WTPART", List.of(before, version(1, "U", 120, "A", "0", "2")))
            .isEmpty(), "noise-only UPDATE is omitted");
      check("LOGICAL_DELETE".equals(only(REDUCER.reduce("WTPART",
            List.of(before, version(1, "U", 120, "A", "1", "2")))).getOperation()),
            "zero-to-marked UPDATE is a logical delete");
      check("UPDATE".equals(only(REDUCER.reduce("WTPART", List.of(
            version(1, null, 0, "A", "1", "1"), version(1, "U", 120, "A", "0", "2")))).getOperation()),
            "clearing a delete marker is an UPDATE");
      CapturedChange createUpdate = only(REDUCER.reduce("WTPART",
            List.of(version(1, "I", 110, "A", "0", "1"), update)));
      check("CREATE".equals(createUpdate.getOperation()) && delta(createUpdate, "NAME", null, "B"),
            "insert-update reduces to creation of final values");
      CapturedChange removed = only(REDUCER.reduce("WTPART",
            List.of(before, version(1, "D", 120, "A", "0", "1"))));
      check("DELETE".equals(removed.getOperation()) && delta(removed, "NAME", "A", null),
            "baseline physical deletion preserves original value");
      CapturedChange recreate = only(REDUCER.reduce("WTPART", List.of(before,
            version(1, "D", 120, "A", "0", "1"), version(1, "I", 130, "C", "0", "1"))));
      check("UPDATE".equals(recreate.getOperation()) && delta(recreate, "NAME", "A", "C"),
            "delete-recreate of an existing ID has net UPDATE effect");
      check(delta(only(REDUCER.reduce("WTPART", List.of(
            version(1, null, 0, null, "0", "1"), version(1, "U", 120, "", "0", "2")))), "NAME", null, ""),
            "null and empty comparison values remain distinct");
      check(REDUCER.reduce("WTPART", List.of(
            version(1, "I", 110, "A", "0", "1"), version(1, "D", 130, "A", "0", "1"))).isEmpty(),
            "NET-01: insert-then-delete must be net zero, consistently with SNAPSHOT");
      check(delta(only(REDUCER.reduce("WTPART", List.of(before, update, deleted))), "NAME", "A", null),
            "NET-02: update-then-delete must retain baseline A, not intermediate B, as net old value");
      check(REDUCER.reduce("WTPART", List.of(version(1, "I", 110, "A", "0", "1"),
            update, deleted)).isEmpty(), "insert-update-delete is also absent at both endpoints");
      CapturedChange insertedAgain = only(REDUCER.reduce("WTPART", List.of(
            version(1, "I", 110, "A", "0", "1"), deleted, version(1, "I", 140, "C", "0", "3"))));
      check("CREATE".equals(insertedAgain.getOperation()) && delta(insertedAgain, "NAME", null, "C")
            && insertedAgain.getChangeScn() == 140, "insert-delete-insert keeps only the final creation and metadata");
      CapturedChange deletedAgain = only(REDUCER.reduce("WTPART", List.of(before,
            version(1, "D", 110, "A", "0", "1"), version(1, "I", 120, "B", "0", "2"), deleted)));
      check(delta(deletedAgain, "NAME", "A", null) && deletedAgain.getChangeScn() == 130,
            "delete-recreate-delete retains the original endpoint, not the recreated row");
      CapturedChange firstDelete = only(REDUCER.reduce("WTPART", List.of(
            version(1, "D", 110, "A", "0", "1"), version(1, "I", 120, "B", "0", "2"))));
      check("UPDATE".equals(firstDelete.getOperation()) && delta(firstDelete, "NAME", "A", "B"),
            "an initial DELETE supplies the pre-window state when the ID is later recreated");
      ChangeReducer named = new ChangeReducer(new IdentityResolver() {
         @Override
         public String resolve(String type, long id, Map<String, String> columns) {
            return columns.get("NAME");
         }
      });
      CapturedChange removedOriginal = only(named.reduce("WTPART", List.of(before, update, deleted)));
      check("A".equals(removedOriginal.getObjectIdentity()) && removedOriginal.getChangeScn() == 130
            && WHEN.equals(removedOriginal.getChangeTime()) && "ABC".equals(removedOriginal.getTransactionId()),
            "DELETE identity comes from the old endpoint while commit metadata remains the final DELETE");
      expect(IllegalArgumentException.class, () -> REDUCER.reduce("WTPART", List.of(update, deleted)),
            "missing pre-window UPDATE state fails explicitly instead of inventing old values");
      check(REDUCER.reduce("WTPART", List.of(before,
            version(1, "D", 120, "A", "0", "1"), version(1, "I", 130, "A", "0", "2"))).isEmpty(),
            "delete-recreate with identical endpoints is net zero");
   }

   private static void snapshots() throws Exception {
      Object[][] before = {{1L, "A", "0", "1"}, {2L, "B", "0", "1"}, {4L, "same", "0", "1"}};
      Object[][] after = {{1L, "C", "0", "2"}, {3L, "D", "0", "1"}, {4L, "same", "0", "2"}};
      Jdbc jdbc = new Jdbc((sql, binds) -> tableRows(sql.contains("AS OF SCN 99 ") ? before : after));
      SnapshotCollector.Result exact = new SnapshotCollector(jdbc.connection(), REDUCER).collect("WTPART", 100, 200, 3);
      check(exact.getChanges().stream().map(CapturedChange::getOperation).collect(Collectors.toList())
            .equals(List.of("UPDATE", "DELETE", "CREATE")), "ordered endpoint merge covers UPDATE/DELETE/CREATE");
      check(!exact.isTruncated(), "exactly-at-cap endpoint changes are not falsely truncated");
      check(exact.getChanges().stream().allMatch(c -> c.getChangeScn() == 0
            && c.getChangeTime() == null && c.getTransactionId() == null), "SNAPSHOT does not invent commit metadata");
      check(jdbc.sql.get(0).contains("AS OF SCN 99 ") && jdbc.sql.get(1).contains("AS OF SCN 200 ")
            && jdbc.sql.stream().allMatch(s -> s.endsWith("ORDER BY t.IDA2A2")),
            "SNAPSHOT uses inclusive lower-bound predecessor and ordered fixed endpoints");
      check(jdbc.closedStatements == 2 && jdbc.closedRows == 2, "SNAPSHOT closes both owned statements/results");
      SnapshotCollector.Result limited = new SnapshotCollector(jdbc.connection(), REDUCER).collect("WTPART", 100, 200, 2);
      check(limited.getChanges().size() == 2 && limited.isTruncated(), "one excess net change discloses truncation");
      Jdbc empty = new Jdbc((sql, binds) -> tableRows(new Object[0][]));
      check(new SnapshotCollector(empty.connection(), REDUCER).collect("WTPART", 100, 200, 1)
            .getChanges().isEmpty(), "empty endpoints have no artificial changes");
      for (long[] values : new long[][] {{0, 200, 1}, {201, 200, 1}, {100, 200, 0}, {100, 200, -1}}) {
         expect(IllegalArgumentException.class,
               () -> new SnapshotCollector(empty.connection(), REDUCER).collect("WTPART", values[0], values[1], (int) values[2]),
               "invalid snapshot bounds/caps fail before JDBC reads");
      }
      expect(IllegalArgumentException.class, () -> new SnapshotCollector(empty.connection(), REDUCER)
            .collect("WTPART;DROP", 100, 200, 1), "snapshot identifiers reject SQL syntax");
   }

   private static void flashback() throws Exception {
      Jdbc fallback = new Jdbc((sql, binds) -> {
         if (sql.contains("DBMS_FLASHBACK")) throw new SQLException("synthetic unavailable", "42000", 904);
         return rows(new String[] {"SCN"}, new int[] {Types.BIGINT}, new Object[][] {{9_876_543_210L}});
      });
      check(new FlashbackCollector(fallback.connection()).currentScn() == 9_876_543_210L
            && fallback.sql.size() == 2, "SCN fallback preserves 64-bit values");
      Jdbc noScn = new Jdbc((sql, binds) -> rows(new String[] {"SCN"}, new int[] {Types.BIGINT}, new Object[0][]));
      expect(SQLException.class, () -> new FlashbackCollector(noScn.connection()).currentScn(),
            "two empty SCN sources are an explicit failure");
      Jdbc chunks = new Jdbc((sql, binds) -> {
         if (sql.contains("SYSTIMESTAMP")) return textRows("+08:00");
         return versions(new Object[0][]);
      });
      new FlashbackCollector(chunks.connection()).versionsForRows("WTPART", 100, 200,
            java.util.stream.LongStream.rangeClosed(1, 1001).boxed().collect(Collectors.toList()));
      check(chunks.parameters.stream().map(Map::size).collect(Collectors.toList()).equals(List.of(1000, 1)),
            "1001 version IDs become two bound chunks, never interpolated ID text");
      check(chunks.sql.stream().filter(s -> s.contains("VERSIONS BETWEEN"))
            .allMatch(s -> s.contains("SCN 100 AND 200 AS OF SCN 200")),
            "every Version Query chunk is anchored to the saved Stop snapshot");
      check(chunks.sql.stream().filter(s -> s.contains("SYSTIMESTAMP")).count() == 1,
            "database clock offset is read once per collector");
      for (int code : new int[] {1555, 30052, 1031, 1466}) {
         Jdbc failed = new Jdbc((sql, binds) -> { throw new SQLException("synthetic", "72000", code); });
         try {
            new FlashbackCollector(failed.connection()).changedRowIds("WTPART", 100, 200);
            check(false, "Oracle " + code + " must be explicitly classified");
         } catch (FlashbackCollector.UndoUnavailableException expected) {
            check(expected.canCompareSnapshots() == (code == 1555 || code == 30052)
                  && ((SQLException) expected.getCause()).getErrorCode() == code,
                  "only undo/snapshot errors permit endpoint fallback: ORA-" + code);
         }
      }
      Jdbc missing = new Jdbc((sql, binds) -> { throw new SQLException("synthetic", "42000", 942); });
      expect(SQLException.class, () -> new FlashbackCollector(missing.connection()).changedRowIds("WTPART", 100, 200),
            "arbitrary SQL failures are not relabelled as undo exhaustion");
      ResultSet nullId = tableRows(new Object[][] {{null, "x", "0", "1"}});
      nullId.next();
      expect(SQLException.class, () -> FlashbackCollector.readRow(nullId, nullId.getMetaData(), 1, null, null, 0, null),
            "null row identities are explicitly rejected");
   }

   private static void locks() throws Exception {
      Jdbc start = new Jdbc((sql, binds) -> textRows());
      CaptureConcurrency.lockForStart(start.connection());
      check(start.sql.equals(List.of("LOCK TABLE DBCAPTURESESSION IN EXCLUSIVE MODE NOWAIT")),
            "Start uses NOWAIT exclusive module-table lock");
      check(start.options.contains("setQueryTimeout=10") && start.closedStatements == 1,
            "Start lock has finite timeout and closes its statement, not the connection");
      for (int count : new int[] {0, 1, 2}) {
         Jdbc stop = new Jdbc((sql, binds) -> rows(new String[] {"IDA2A2"}, new int[] {Types.BIGINT},
               count == 0 ? new Object[0][] : count == 1 ? new Object[][] {{91L}} : new Object[][] {{91L}, {92L}}));
         if (count == 2) {
            expect(SQLException.class, () -> CaptureConcurrency.lockRunningSession(stop.connection()),
                  "multiple RUNNING rows fail rather than selecting an arbitrary owner");
         } else {
            check(Objects.equals(CaptureConcurrency.lockRunningSession(stop.connection()), count == 0 ? null : 91L),
                  "Stop running-session cardinality " + count);
         }
         check(stop.sql.get(0).endsWith("STATUS=? FOR UPDATE NOWAIT")
               && "RUNNING".equals(stop.parameters.get(0).get(1)), "Stop locks only bound RUNNING sessions");
         check(stop.closedRows == 1 && stop.closedStatements == 1, "Stop closes lock-query resources");
      }
      for (int code : new int[] {54, 1031}) {
         Jdbc busy = new Jdbc((sql, binds) -> { throw new SQLException("synthetic", "72000", code); });
         try {
            CaptureConcurrency.lockRunningSession(busy.connection());
            check(false, "lock error propagates");
         } catch (SQLException expected) {
            check(expected.getErrorCode() == code && (code != 54 || expected.getMessage().contains("already running")),
                  "lock error retains Oracle code and gives NOWAIT contention guidance");
         }
      }
   }

   private static void identifierBounds() throws Exception {
      String exact = "N".repeat(40);
      String tooLong = exact + "X";
      check(FlashbackCollector.quote(exact).equals("\"" + exact + "\""),
            "40-character physical table identifiers are preserved exactly");
      for (String unsupported : Arrays.asList(tooLong, "QuotedTable", "wtpart", null, "")) {
         expect(IllegalArgumentException.class, () -> FlashbackCollector.quote(unsupported),
               "unsupported physical identifiers are refused rather than truncated or case-folded");
      }
      ResultSet columns = rows(new String[] {"IDA2A2", "NAME", "Name", exact},
            new int[] {Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR},
            new Object[][] {{9_007_199_254_740_993L, "upper", "mixed", "boundary"}});
      columns.next();
      RowVersion preserved = FlashbackCollector.readRow(columns, columns.getMetaData(), 1, "I", WHEN, 120, "ABC");
      check(preserved.getRowId() == 9_007_199_254_740_993L
            && preserved.getColumns().size() == 4 && "upper".equals(preserved.get("NAME"))
            && "mixed".equals(preserved.get("Name")) && "boundary".equals(preserved.get(exact)),
            "exact long row IDs, case-distinct columns and 40-character columns cannot alias");
      ResultSet oversized = rows(new String[] {"IDA2A2", tooLong},
            new int[] {Types.BIGINT, Types.VARCHAR}, new Object[][] {{1L, "value"}});
      oversized.next();
      expect(SQLException.class, () -> FlashbackCollector.readRow(
            oversized, oversized.getMetaData(), 1, "I", WHEN, 120, "ABC"),
            "overlength column identifiers fail before any persisted alias can be produced");
      Jdbc jdbc = new Jdbc((sql, binds) -> {
         if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
         if (sql.contains("USER_TABLES")) return catalogRows("WTPART", "WTDOCUMENT");
         if (sql.contains("SYSTIMESTAMP")) return textRows("+00:00");
         if (sql.contains("SELECT DISTINCT")) return sql.contains("\"WTPART\"") ? scalar(1L) : scalar();
         if (sql.contains("VERSIONS_XID")) return rows(new String[] {
               "VERSIONS_XID", "VERSIONS_OPERATION", "VERSIONS_STARTSCN", "VERSIONS_STARTTIME", "IDA2A2", tooLong},
               new int[] {Types.VARBINARY, Types.VARCHAR, Types.BIGINT, Types.TIMESTAMP, Types.BIGINT, Types.VARCHAR},
               new Object[][] {{new byte[] {1}, "I", 150L, WHEN, 1L, "value"}});
         throw new AssertionError("Unexpected identifier-bound query: " + sql);
      });
      CaptureResult result = new CaptureEngine(jdbc.connection()).end(baseline(), null, WHEN, false);
      check(result.getTablesExamined() == 2 && result.getChanges().isEmpty() && !result.hasSnapshotFallback()
            && result.warningText(4000).contains(tooLong) && result.warningText(4000).contains("40-character"),
            "overlength columns produce an explicit per-table warning without inventing aliases or undo recovery");
   }

   private static void engineRecovery() throws Exception {
      for (int code : new int[] {1555, 30052}) for (boolean match : new boolean[] {true, false}) {
         AtomicInteger attempts = new AtomicInteger();
         Jdbc jdbc = new Jdbc((sql, binds) -> {
            if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
            if (sql.contains("USER_TABLES")) return catalogRows("WTPART");
            if (sql.contains("SELECT DISTINCT")) {
               attempts.incrementAndGet();
               throw new SQLException("synthetic undo", "72000", code);
            }
            if (sql.contains("SYSTIMESTAMP")) return textRows("+00:00");
            if (sql.contains("VERSIONS_XID")) return versions(new Object[][] {
                  {null, null, null, null, 1L, "A", "0", "1"},
                  {new byte[] {1}, "U", 150L, WHEN, 1L, match ? "B" : "C", "0", "2"}});
            return tableRows(new Object[][] {{1L, sql.contains("AS OF SCN 99 ") ? "A" : "B", "0", "1"}});
         });
         CaptureResult result = new CaptureEngine(jdbc.connection()).end(baseline(), null, WHEN, false);
         check(attempts.get() == (code == 1555 ? 2 : 1),
               "only ORA-01555 retries once; ORA-30052 falls back directly");
         check(result.getEndScn() == 200 && result.hasSnapshotFallback() && result.getTablesChanged() == 1,
               "endpoint recovery records exact end SCN, changed tables and mixed mode");
         CapturedChange recovered = only(result.getChanges());
         check(delta(recovered, "NAME", "A", "B"), "endpoint deltas win over mismatching history");
         check(recovered.getChangeScn() == (match ? 150 : 0)
               && result.warningText(4000).contains(match ? "ID-limited Version Query" : "Snapshot recovery"),
               "commit metadata is accepted only for exact endpoint/history agreement");
      }
      for (int code : new int[] {1031, 1466, 942}) {
         Jdbc jdbc = new Jdbc((sql, binds) -> {
            if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
            if (sql.contains("USER_TABLES")) return catalogRows("WTPART", "WTDOCUMENT");
            if (sql.contains("\"WTPART\"")) throw new SQLException("synthetic missing access or DDL", "42000", code);
            if (sql.contains("SELECT DISTINCT")) return scalar();
            throw new AssertionError("Unapproved recovery query: " + sql);
         });
         CaptureResult result = new CaptureEngine(jdbc.connection()).end(baseline(), null, WHEN, false);
         check(!result.hasSnapshotFallback() && result.getTablesExamined() == 2
               && jdbc.sql.stream().anyMatch(s -> s.contains("\"WTDOCUMENT\""))
               && result.warningText(4000).contains("WTPART"),
               "non-recoverable table error is explicit and collection continues: ORA-" + code);
         check(jdbc.sql.stream().noneMatch(s -> s.startsWith("SELECT t.*")),
               "access/definition/arbitrary failure never runs endpoint fallback: ORA-" + code);
      }
      for (boolean match : new boolean[] {true, false}) {
         Jdbc jdbc = new Jdbc((sql, binds) -> {
            if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
            if (sql.contains("USER_TABLES")) return catalogRows("WTPART");
            if (sql.contains("SELECT DISTINCT")) throw new SQLException("synthetic undo", "72000", 30052);
            if (sql.contains("SYSTIMESTAMP")) return textRows("+00:00");
            if (sql.contains("VERSIONS_XID")) return versions(new Object[][] {
                  {null, null, null, null, 1L, match ? "A" : "X", "0", "1"},
                  {new byte[] {1}, "U", 120L, WHEN, 1L, "B", "0", "2"},
                  {new byte[] {2}, "D", 150L, WHEN, 1L, "B", "0", "2"}});
            return tableRows(sql.contains("AS OF SCN 99 ")
                  ? new Object[][] {{1L, "A", "0", "1"}} : new Object[0][]);
         });
         CaptureResult result = new CaptureEngine(jdbc.connection()).end(baseline(), null, WHEN, false);
         CapturedChange recovered = only(result.getChanges());
         check("DELETE".equals(recovered.getOperation()) && delta(recovered, "NAME", "A", null),
               "hybrid UPDATE-DELETE agrees with the original endpoint, never intermediate B");
         check(recovered.getChangeScn() == (match ? 150 : 0)
               && Objects.equals(recovered.getTransactionId(), match ? "02" : null),
               "hybrid DELETE promotes final commit metadata only when the original endpoint matches");
      }
      Jdbc incomplete = new Jdbc((sql, binds) -> {
         if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
         if (sql.contains("USER_TABLES")) return catalogRows("WTPART");
         if (sql.contains("SELECT DISTINCT")) throw new SQLException("synthetic undo", "72000", 30052);
         if (sql.contains("SYSTIMESTAMP")) return textRows("+00:00");
         if (sql.contains("VERSIONS_XID")) return versions(new Object[][] {
               {new byte[] {1}, "U", 120L, WHEN, 1L, "B", "0", "2"},
               {new byte[] {2}, "D", 150L, WHEN, 1L, "B", "0", "2"}});
         return tableRows(sql.contains("AS OF SCN 99 ")
               ? new Object[][] {{1L, "A", "0", "1"}} : new Object[0][]);
      });
      CaptureResult fallback = new CaptureEngine(incomplete.connection()).end(baseline(), null, WHEN, false);
      CapturedChange retained = only(fallback.getChanges());
      check(delta(retained, "NAME", "A", null) && retained.getChangeScn() == 0
            && retained.getTransactionId() == null && fallback.warningText(4000).contains("Snapshot recovery"),
            "incomplete ID-limited history cannot erase valid endpoint evidence or invent its commit metadata");
      Jdbc partial = new Jdbc((sql, binds) -> {
         if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
         if (sql.contains("USER_TABLES")) return catalogRows("A_BAD", "WTPART");
         if (sql.contains("SELECT DISTINCT")) return scalar(1L);
         if (sql.contains("SYSTIMESTAMP")) return textRows("+00:00");
         if (sql.contains("VERSIONS_XID")) return versions(sql.contains("\"A_BAD\"")
               ? new Object[][] {{new byte[] {1}, "U", 150L, WHEN, 1L, "B", "0", "2"}}
               : new Object[][] {{null, null, null, null, 1L, "A", "0", "1"},
                     {new byte[] {1}, "U", 150L, WHEN, 1L, "B", "0", "2"}});
         throw new AssertionError("Unexpected incomplete-history query: " + sql);
      });
      CaptureResult isolated = new CaptureEngine(partial.connection()).end(baseline(), null, WHEN, false);
      check(isolated.getTablesExamined() == 2 && isolated.getTablesChanged() == 1
            && only(isolated.getChanges()).getTableName().equals("WTPART")
            && isolated.warningText(4000).contains("Missing pre-window state for A_BAD IDA2A2=1")
            && !isolated.hasSnapshotFallback(),
            "missing baseline values are an explicit per-table failure; later tables still retain their exact evidence");
   }

   private static void engineScope() throws Exception {
      Jdbc physical = new Jdbc((sql, binds) -> catalogRows("WTPART", "QuotedTable", "DBCAPTURESESSION"));
      List<String> selected = new CaptureEngine(physical.connection()).allIncludedTables(new TableFilter(new Properties(), ""));
      check(selected.equals(List.of("WTPART")),
            "SCOPE-01: row collector must exclude quoted mixed-case tables just as MonitoringScope.Catalog does");
      AtomicInteger clock = new AtomicInteger();
      AtomicInteger activity = new AtomicInteger();
      Jdbc changing = new Jdbc((sql, binds) -> {
         if (sql.contains("DBMS_FLASHBACK")) return scalar(clock.incrementAndGet() == 1 ? 100L : 200L);
         if (sql.contains("NVL(INSERTS")) {
            return rows(new String[] {"TABLE_NAME", "INSERTS", "UPDATES", "DELETES"},
                  new int[] {Types.VARCHAR, Types.BIGINT, Types.BIGINT, Types.BIGINT},
                  activity.incrementAndGet() == 1 ? new Object[0][] :
                  new Object[][] {{"WTPART", 1L, 0L, 0L}, {"CREATED_AFTER_START", 1L, 0L, 0L}});
         }
         if (sql.contains("USER_TABLES")) return clock.get() == 1 ? catalogRows("WTPART")
               : catalogRows("WTPART", "CREATED_AFTER_START");
         if (sql.contains("SELECT DISTINCT")) return scalar();
         throw new AssertionError("Unexpected scope query: " + sql);
      });
      CaptureEngine engine = new CaptureEngine(changing.connection());
      CaptureEngine.CaptureBaseline frozen = engine.begin();
      CaptureResult result = engine.end(frozen, null, WHEN, false);
      check(result.getTablesExamined() == 1
            && changing.sql.stream().noneMatch(s -> s.contains("\"CREATED_AFTER_START\"")),
            "SCOPE-02: physical start catalog must be frozen; newly created tables cannot join at Stop");
      check(changing.sql.stream().filter(s -> s.contains("USER_TABLES")).count() == 1
            && frozen.getCatalog().getTableNames().equals(List.of("WTPART")),
            "Start reads one immutable physical catalog shared by collection and diagnostics");
      expect(UnsupportedOperationException.class, () -> frozen.getCatalog().getTableNames().clear(),
            "the retained physical catalog cannot be changed by a caller");
      check(frozen.getScope() != null && CaptureEngine.baselineFromScn(100, "lost original scope").getScope() == null,
            "cross-node baseline explicitly lacks original in-memory filter; no false preservation claim");
      Jdbc unflushed = new Jdbc((sql, binds) -> {
         if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
         if (sql.contains("SYSTIMESTAMP")) return textRows("+00:00");
         if (sql.contains("USER_TAB_MODIFICATIONS")) return textRows();
         if (sql.contains("USER_TABLES")) return catalogRows("WTPART");
         if (sql.contains("SELECT DISTINCT")) return scalar();
         throw new AssertionError("Unexpected unflushed activity query: " + sql);
      });
      unflushed.flushFailure = new SQLException("synthetic flush permission denied", "42000", 1031);
      CaptureEngine noFlush = new CaptureEngine(unflushed.connection());
      CaptureResult unreadableActivity = noFlush.end(noFlush.begin(), WHEN, WHEN, false);
      check(unreadableActivity.getTablesExamined() == 1,
            "ACTIVITY-01: failed monitoring flush must not treat stale/empty modification timestamps as proof of no changes");
      check(unflushed.sql.stream().noneMatch(s -> s.contains("WHERE TIMESTAMP"))
            && unreadableActivity.warningText(4000).contains("synthetic flush permission denied"),
            "unavailable Start monitoring retains its cause and never consults stale timestamps");
      Jdbc unavailableCatalog = new Jdbc((sql, binds) -> {
         if (sql.contains("DBMS_FLASHBACK")) return scalar(100L);
         throw new SQLException("synthetic catalog denied", "42000", 1031);
      });
      try {
         new CaptureEngine(unavailableCatalog.connection()).begin();
         check(false, "Start must not fabricate an empty catalog after metadata failure");
      } catch (SQLException failure) {
         check(failure.getErrorCode() == 1031 && "42000".equals(failure.getSQLState())
               && failure.getMessage().equals("synthetic catalog denied"),
               "Start catalog failure preserves its original message, SQLState and Oracle code");
      }
      AtomicInteger caseReads = new AtomicInteger();
      Jdbc distinctNames = new Jdbc((sql, binds) -> {
         if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
         if (sql.contains("USER_TABLES")) return catalogRows("QUOTEDTABLE", "QuotedTable");
         if (sql.contains("NVL(INSERTS")) return rows(
               new String[] {"TABLE_NAME", "INSERTS", "UPDATES", "DELETES"},
               new int[] {Types.VARCHAR, Types.BIGINT, Types.BIGINT, Types.BIGINT},
               new Object[][] {{"QUOTEDTABLE", caseReads.getAndIncrement() == 0 ? 0L : 1L, 0L, 0L},
                     {"QuotedTable", 50L, 0L, 0L}});
         if (sql.contains("SELECT DISTINCT")) return scalar();
         throw new AssertionError("Unexpected exact-name query: " + sql);
      });
      CaptureEngine exactNames = new CaptureEngine(distinctNames.connection());
      CaptureResult exactScope = exactNames.end(exactNames.begin(), null, WHEN, false);
      check(exactScope.getTablesExamined() == 1
            && distinctNames.sql.stream().anyMatch(s -> s.contains("\"QUOTEDTABLE\""))
            && distinctNames.sql.stream().noneMatch(s -> s.contains("\"QuotedTable\"")),
            "activity for a quoted mixed-case table cannot overwrite counters for a distinct uppercase physical table");
   }

   private static void activityFallbacks() throws Exception {
      for (String failure : List.of("stop-flush", "start-counters", "stop-counters", "none", "timestamps")) {
         AtomicInteger reads = new AtomicInteger();
         Jdbc jdbc = new Jdbc((sql, binds) -> {
            if (sql.contains("DBMS_FLASHBACK")) return scalar(200L);
            if (sql.contains("USER_TABLES")) return catalogRows("WTPART", "WTDOCUMENT", "QuotedTable");
            if (sql.contains("SYSTIMESTAMP")) return textRows("+00:00");
            if (sql.contains("NVL(INSERTS")) {
               int read = reads.incrementAndGet();
               if (failure.equals("start-counters") && read == 1
                     || failure.equals("stop-counters") && read == 2) {
                  throw new SQLException("synthetic counters unavailable", "42000", 942);
               }
               return rows(new String[] {"TABLE_NAME", "INSERTS", "UPDATES", "DELETES"},
                     new int[] {Types.VARCHAR, Types.BIGINT, Types.BIGINT, Types.BIGINT}, new Object[0][]);
            }
            if (sql.contains("WHERE TIMESTAMP")) return failure.equals("timestamps")
                  ? textRows("WTPART", "NOT_IN_START_CATALOG", "QuotedTable") : textRows();
            if (sql.contains("SELECT DISTINCT")) return scalar();
            throw new AssertionError("Unexpected activity query: " + sql);
         });
         CaptureEngine engine = new CaptureEngine(jdbc.connection());
         CaptureEngine.CaptureBaseline start = engine.begin();
         if (failure.equals("stop-flush")) {
            jdbc.flushFailure = new SQLException("synthetic Stop flush denied", "42000", 1031);
         }
         CaptureResult result = engine.end(start, WHEN, WHEN, false);
         int expected = failure.equals("none") ? 0 : failure.equals("timestamps") ? 1 : 2;
         check(result.getTablesExamined() == expected, "freshness-aware scope selection: " + failure);
         check(jdbc.sql.stream().filter(s -> s.contains("USER_TABLES")).count() == 1,
               "activity fallback never expands the Start physical catalog: " + failure);
         if (expected == 2) {
            check(jdbc.sql.stream().noneMatch(s -> s.contains("WHERE TIMESTAMP"))
                  && result.warningText(4000).contains("examining every included table"),
                  "unavailable counters or flush cannot be overridden by empty timestamps: " + failure);
         }
      }
   }

   private static void objectReads() throws Exception {
      Object[][] many = new Object[101][];
      for (int i = 0; i < many.length; i++) many[i] = new Object[] {42L, "visible"};
      Jdbc bounded = new Jdbc((sql, binds) -> rows(new String[] {"IDA2A2", "NAME"},
            new int[] {Types.BIGINT, Types.VARCHAR}, sql.contains("WHERE 1=0") ? new Object[0][] : many));
      DbCaptureObjectReader.Snapshot snapshot = DbCaptureObjectReader.read(bounded.connection(), "wtpart", 42,
            "wt.part.WTPart");
      check(snapshot.rows().size() == 100 && snapshot.truncated(), "current-object inspection enforces 100-row bound");
      check(snapshot.reference().equals("wt.part.WTPart:42") && snapshot.table().equals("WTPART"),
            "current-object reference retains captured class and numeric row identity");
      check(bounded.sql.get(1).contains("WHERE \"IDA2A2\" = ?")
            && bounded.sql.get(1).endsWith("FETCH FIRST 101 ROWS ONLY")
            && bounded.parameters.get(1).get(1).equals(42L), "object query never interpolates untrusted row identity");
      check(bounded.options.contains("setQueryTimeout=10") && bounded.options.contains("setMaxRows=101")
            && bounded.closedStatements == 2 && bounded.closedRows == 2,
            "object queries have time/row bounds and release owned JDBC resources");
      Jdbc deleted = new Jdbc((sql, binds) -> rows(new String[] {"IDA2A2"}, new int[] {Types.BIGINT}, new Object[0][]));
      check(DbCaptureObjectReader.read(deleted.connection(), "WTPART", 42, null).rows().isEmpty(),
            "missing current row is empty, never substituted with historical data");
      for (String table : List.of("WTPART;DROP", "SCHEMA.WTPART", "\"WTPART\"", "A".repeat(129))) {
         expect(IllegalArgumentException.class, () -> DbCaptureObjectReader.read(deleted.connection(), table, 42, null),
               "current-object table identifier refuses SQL syntax or unsupported length");
      }
      expect(IllegalArgumentException.class, () -> DbCaptureObjectReader.read(deleted.connection(), "WTPART", 0, null),
            "current-object row ID must be positive");
      String longText = "x".repeat(DbCaptureObjectReader.VALUE_LIMIT + 1);
      Jdbc values = new Jdbc((sql, binds) -> rows(new String[] {"IDA2A2", "TEXTVALUE", "BINARYVALUE"},
            new int[] {Types.BIGINT, Types.CLOB, Types.VARBINARY},
            sql.contains("WHERE 1=0") ? new Object[0][] :
            new Object[][] {{42L, longText, new byte[DbCaptureObjectReader.VALUE_LIMIT + 1]}}));
      List<String> rendered = DbCaptureObjectReader.read(values.connection(), "WTPART", 42, null).rows().get(0);
      check(rendered.get(1).equals("x".repeat(2000) + "\n[value truncated]"),
            "current-object CLOB previews read a bounded prefix and disclose truncation");
      check(rendered.get(2).equals("[binary: more than 2000 bytes; content not displayed]"),
            "current-object binary previews do not expose content");
   }

   private static void identities() throws Exception {
      DbCaptureSession session = new DbCaptureSession();
      session.setStartedBy("owner");
      check(DbCaptureAuthorization.isOwner(session, "owner"), "same username is owner independently of browser");
      check(!DbCaptureAuthorization.isOwner(session, "other")
            && !DbCaptureAuthorization.isOwner(session, "Owner")
            && !DbCaptureAuthorization.isOwner(session, null)
            && !DbCaptureAuthorization.isOwner(null, "owner"), "owner comparison fails closed for other/absent users");
      session.setStartedBy(null);
      check(!DbCaptureAuthorization.isOwner(session, "owner"), "unknown owner is not silently adopted");
      check(DbCaptureObjectReader.positiveId("9223372036854775807") == Long.MAX_VALUE,
            "maximum persisted signed-long identity survives");
      for (String id : Arrays.asList(null, "", "0", "-1", "+1", " 1", "1.0", "1 OR 1=1", "9223372036854775808")) {
         expect(IllegalArgumentException.class, () -> DbCaptureObjectReader.positiveId(id),
               "invalid captured entry identity is rejected");
      }
      String oid = "com.custom.dbcapture.DbCaptureSession:42";
      check(SessionEvidenceStore.canonicalSessionOid("OR:" + oid).equals(oid),
            "private evidence uses canonical persistent OID, not recycled display capture ID");
      for (String id : List.of("CAP-000042", "wt.part.WTPart:42", "../../42", "com.custom.dbcapture.DbCaptureSession:0")) {
         expect(IllegalArgumentException.class, () -> SessionEvidenceStore.canonicalSessionOid(id),
               "foreign, display-only or malformed evidence identities are refused");
      }
   }

   private static CaptureEngine.CaptureBaseline baseline() {
      return new CaptureEngine.CaptureBaseline(100, null, null, new TableFilter(new Properties(), ""));
   }

   private static RowVersion version(long id, String operation, long scn, String name, String mark, String noise) {
      Map<String, String> columns = new LinkedHashMap<>();
      columns.put("IDA2A2", Long.toString(id));
      columns.put("NAME", name);
      columns.put("MARKFORDELETEA2", mark);
      columns.put("UPDATECOUNTA2", noise);
      return new RowVersion(id, operation, operation == null ? null : WHEN, scn,
            operation == null ? null : "ABC", columns);
   }

   private static CapturedChange only(List<CapturedChange> changes) {
      if (changes.size() != 1) throw new AssertionError("Expected one fixture change, got " + changes.size());
      return changes.get(0);
   }

   private static boolean delta(CapturedChange change, String name, String before, String after) {
      return change.getDeltas().stream().anyMatch(d -> name.equals(d.getColumn())
            && Objects.equals(before, d.getOldValue()) && Objects.equals(after, d.getNewValue()));
   }

   private static void check(boolean condition, String label) {
      if (condition) passed++; else failures.add(label);
   }

   private static void expect(Class<? extends Throwable> type, Action action, String label) throws Exception {
      try {
         action.run();
         check(false, label);
      } catch (Throwable error) {
         if (!type.isInstance(error)) throw new AssertionError(label, error);
         passed++;
      }
   }

   private static void restore(Properties properties, String key, Object value) {
      if (value == null) properties.remove(key); else properties.put(key, value);
   }

   private static ResultSet scalar(Long... values) {
      return rows(new String[] {"VALUE"}, new int[] {Types.BIGINT},
            Arrays.stream(values).map(value -> new Object[] {value}).toArray(Object[][]::new));
   }

   private static ResultSet textRows(String... values) {
      return rows(new String[] {"TABLE_NAME"}, new int[] {Types.VARCHAR},
            Arrays.stream(values).map(value -> new Object[] {value}).toArray(Object[][]::new));
   }

   private static ResultSet catalogRows(String... values) {
      return rows(new String[] {"TABLE_NAME", "HAS_ROW_ID"}, new int[] {Types.VARCHAR, Types.INTEGER},
            Arrays.stream(values).map(value -> new Object[] {value, 1}).toArray(Object[][]::new));
   }

   private static ResultSet tableRows(Object[][] values) {
      return rows(new String[] {"IDA2A2", "NAME", "MARKFORDELETEA2", "UPDATECOUNTA2"},
            new int[] {Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR}, values);
   }

   private static ResultSet versions(Object[][] values) {
      return rows(new String[] {"VERSIONS_XID", "VERSIONS_OPERATION", "VERSIONS_STARTSCN", "VERSIONS_STARTTIME",
            "IDA2A2", "NAME", "MARKFORDELETEA2", "UPDATECOUNTA2"},
            new int[] {Types.VARBINARY, Types.VARCHAR, Types.BIGINT, Types.TIMESTAMP,
                  Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR}, values);
   }

   private static ResultSet rows(String[] names, int[] types, Object[][] values) {
            ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (p, method, args) -> {
                  switch (method.getName()) {
                        case "getColumnCount": return names.length;
                        case "getColumnName":
                        case "getColumnLabel": return names[(int) args[0] - 1];
                        case "getColumnType": return types[(int) args[0] - 1];
                        default: throw new AssertionError("Unexpected metadata call: " + method.getName());
                  }
      });
      int[] index = {-1};
      boolean[] wasNull = {false};
      return proxy(ResultSet.class, (p, method, args) -> {
         switch (method.getName()) {
            case "next": return ++index[0] < values.length;
            case "close": return null;
            case "getMetaData": return metadata;
            case "wasNull": return wasNull[0];
            default:
               Object value = values[index[0]][(int) args[0] - 1];
               wasNull[0] = value == null;
                              switch (method.getName()) {
                                    case "getLong": return value == null ? 0L : ((Number) value).longValue();
                                    case "getInt": return value == null ? 0 : ((Number) value).intValue();
                                    case "getString": return value == null ? null : value.toString();
                                    case "getBytes":
                                    case "getTimestamp": return value;
                                    case "getCharacterStream":
                                          return value == null ? null : new java.io.StringReader(value.toString());
                                    case "getBinaryStream":
                                          return value == null ? null : new java.io.ByteArrayInputStream((byte[]) value);
                                    default: throw new AssertionError("Unexpected ResultSet call: " + method.getName());
                              }
         }
      });
   }

   private static <T> T proxy(Class<T> type, InvocationHandler handler) {
      return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
   }

   private interface Action { void run() throws Exception; }
   private interface Query { ResultSet run(String sql, Map<Integer, Object> binds) throws SQLException; }

   private static final class Jdbc {
      private final Query query;
      private final List<String> sql = new ArrayList<>();
      private final List<Map<Integer, Object>> parameters = new ArrayList<>();
      private final List<String> options = new ArrayList<>();
      private int closedStatements;
      private int closedRows;
      private SQLException flushFailure;

      private Jdbc(Query query) { this.query = query; }

      private Connection connection() {
                  return proxy(Connection.class, (p, method, args) -> {
                        switch (method.getName()) {
                              case "prepareStatement": return statement((String) args[0], true);
                              case "createStatement": return statement(null, false);
                              default: throw new AssertionError("No connection ownership/transaction calls allowed: " + method.getName());
                        }
         });
      }

      private Statement statement(String preparedSql, boolean prepared) {
         Map<Integer, Object> binds = new LinkedHashMap<>();
         InvocationHandler handler = (p, method, args) -> {
            switch (method.getName()) {
               case "close": closedStatements++; return null;
                              case "setQueryTimeout":
                              case "setFetchSize":
                              case "setMaxRows":
                  options.add(method.getName() + "=" + args[0]); return null;
                              case "setLong":
                              case "setString":
                              case "setTimestamp":
                  binds.put((int) args[0], args[1]); return null;
               case "execute":
                  String command = (String) args[0];
                  if (!command.equals("BEGIN DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO; END;")
                        && !command.equals("LOCK TABLE DBCAPTURESESSION IN EXCLUSIVE MODE NOWAIT")) {
                     throw new AssertionError("No mutation is permitted even on the JDBC fixture: " + command);
                  }
                  sql.add(command);
                  if (command.startsWith("BEGIN DBMS_STATS") && flushFailure != null) throw flushFailure;
                  return false;
               case "executeQuery":
                  String text = prepared ? preparedSql : (String) args[0];
                  if (!text.startsWith("SELECT ")) throw new AssertionError("Only SELECT fixture queries are allowed");
                  sql.add(text);
                  if (prepared) parameters.add(Map.copyOf(binds));
                  ResultSet result = query.run(text, binds);
                  return proxy(ResultSet.class, (rowProxy, rowMethod, rowArgs) -> {
                     if (rowMethod.getName().equals("close")) closedRows++;
                     try {
                        return rowMethod.invoke(result, rowArgs);
                     } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                     }
                  });
               default: throw new AssertionError("Unexpected statement operation: " + method.getName());
            }
         };
         return prepared ? proxy(PreparedStatement.class, handler) : proxy(Statement.class, handler);
      }
   }
}
