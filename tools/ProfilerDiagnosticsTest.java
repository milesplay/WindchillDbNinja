package com.custom.dbcapture.diagnostics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.MarkerFilter;

/** Standalone fixtures only: no Windchill API invocation, business connection, or live logger context. */
@SuppressWarnings("try")
public final class ProfilerDiagnosticsTest {
   private static final boolean WINDOWS = System.getProperty("os.name", "")
      .toLowerCase(java.util.Locale.ROOT).contains("windows");
   private static final Set<AclEntryPermission> WRITE_PERMISSIONS = EnumSet.of(
      AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
      AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.WRITE_ATTRIBUTES,
      AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
      AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
   private static int checks;
   private static int nextOid = 810000;
   private static Path base;
   private static final ThreadLocal<TestRequest> REQUEST = new ThreadLocal<TestRequest>();
   private static final List<String> TABLES = Arrays.asList("WTPART", "WTDOCUMENT", "EPMDOCUMENT");

   public static void main(String[] args) throws Exception {
      if (args.length > 0 && args[0].startsWith("--child-")) {
         child(args[0], Path.of(args[1]));
         return;
      }
      base = Path.of(args[0]);
      Files.createDirectories(base);
      classify();
      nativeBatchDml();
      expandedFrozenScope();
      frozenNotRecordedScope();
      filterAndLifetime();
      baselineAndUnavailable();
      qualifiedWindchillFilter();
      multipleContextsRejectTheWholeRequest();
      timeoutAndReconfiguration();
      bounds();
      concurrency();
      checkpointReadConcurrency();
      blockedFinalizationDoesNotHoldRequests();
      storeSafety();
      persistenceFailure();
      quota();
      processLifetime("--child-crash");
      processLifetime("--child-shutdown");
      System.out.println("ProfilerDiagnosticsTest: " + checks + " assertions passed (isolated JVMs; no server changes).");
   }

   private static void expandedFrozenScope() throws Exception {
      List<String> tables = new ArrayList<>();
      tables.addAll(Arrays.asList("WTPART", "CONTROLBRANCH", "WTPARTUSAGELINK"));
      for (int i = 0; i < 2000; i++) tables.add("BUSINESS_OBJECT_WITH_LONG_TABLE_NAME_" + i);
      List<String> catalog = new ArrayList<>(tables);
      catalog.addAll(List.of("OUTSIDE_SCOPE", "DBCAPTURESESSION", "NO_ID_TABLE"));
      try (Fixture fixture = new Fixture("expanded-frozen-scope", Level.ERROR)) {
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, oid(), tables, catalog, SqlEvidence.Limits.defaults());
         check(window.snapshot().getNotRecordedTables().equals(
                     List.of("DBCAPTURESESSION", "NO_ID_TABLE", "OUTSIDE_SCOPE")),
               "the catalog complement is durable while the capture is still active");
         TestRequest request = new TestRequest("expanded-request");
         tables.clear();
         catalog.clear();
         catalog.add("ADDED_AFTER_START");
         inRequest(request, () -> {
            nativeInsert(fixture.sql, "SQL Query:SELECT " + "ignored_column,".repeat(3000) + " FROM WTPART");
            nativeInsert(fixture.sql, "Insert Statement=INSERT INTO CONTROLBRANCH (ida2a2) VALUES (?)");
            nativeInsert(fixture.sql, "Delete=DELETE FROM WTPARTUSAGELINK WHERE idA2A2=?");
            nativeInsert(fixture.sql, "Update=UPDATE BUSINESS_OBJECT_WITH_LONG_TABLE_NAME_1999 SET x=?");
            nativeInsert(fixture.sql, "Delete=DELETE FROM OUTSIDE_SCOPE WHERE idA2A2=?");
            nativeInsert(fixture.sql, "Insert Statement=INSERT INTO DBCAPTURESESSION (ida2a2) VALUES (?)");
         });
         request.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
         window.stop();
         SqlEvidence.Snapshot snapshot = window.snapshot();
         check(snapshot.getEvents().size() == 3, "native DML includes monitored links, branches and custom tables");
         check(snapshot.getTables().size() == 2003, "large explicit scope is persisted, frozen and read back");
         check(snapshot.isNotRecordedScopeAvailable()
               && snapshot.getNotRecordedTables().equals(List.of("DBCAPTURESESSION", "NO_ID_TABLE", "OUTSIDE_SCOPE")),
               "not-recorded scope is the frozen catalog complement, not later settings/catalog contents");
         check(new SessionEvidenceStore(fixture.root).read(window.getSessionOid()).getNotRecordedTables()
                     .equals(snapshot.getNotRecordedTables()),
               "a new reader recovers the complement from persisted evidence, not the active window");
         check(snapshot.getFilteredStatements() == 3, "out-of-scope and diagnostic SQL remain excluded");
         check(snapshot.getLimitDiscardedStatements() == 0,
               "an oversized excluded SELECT cannot exhaust the DML evidence window");
         check(snapshot.getState() == SqlEvidence.State.COMPLETE, "metadata larger than the old 32 KiB cap completes");
      }
      List<String> tooMany = new ArrayList<>();
      for (int i = 0; i <= SqlStatementClassifier.MAX_TABLES; i++) tooMany.add("BUSINESS_" + i);
      expect(IllegalArgumentException.class, () -> new SqlStatementClassifier(tooMany));
   }

   private static void frozenNotRecordedScope() throws Exception {
      try (Fixture fixture = new Fixture("legacy-scope", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         window.stop();
         SqlEvidence.Snapshot legacy = window.snapshot();
         check(!legacy.isNotRecordedScopeAvailable() && legacy.getNotRecordedTables().isEmpty(),
               "legacy metadata explicitly lacks a not-recorded snapshot; empty is never treated as known none");
         check(legacy.getTables().equals(TABLES), "legacy recorded scope is preserved without a catalog lookup");
         Properties metadata = metadata(sessionDirectory(fixture.root).resolve("metadata.properties"));
         check("1".equals(metadata.getProperty("version")) && !metadata.containsKey("notRecordedTableCount"),
               "legacy version-one metadata without the optional snapshot remains readable");
         check(!fixture.store.read(oid()).isNotRecordedScopeAvailable(),
               "absent evidence cannot invent a historical complement");
      }
      List<String> catalog = List.of("WTPART", "WTDOCUMENT", "EPMDOCUMENT", "DBCAPTURESESSION",
            "NO_ID_TABLE", "Quoted, Table", "WTPart");
      try (Fixture fixture = new Fixture("empty-recorded-scope", Level.ERROR)) {
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, oid(), List.of(), catalog, SqlEvidence.Limits.defaults());
         SqlEvidence.Snapshot snapshot = window.snapshot();
         check(snapshot.getState() == SqlEvidence.State.UNAVAILABLE && !window.isActive(),
               "an empty monitored scope persists its snapshot without installing tracing");
         check(snapshot.getTables().isEmpty() && snapshot.getEvents().isEmpty(),
               "an empty monitored scope has neither eligible tables nor invented SQL");
         check(snapshot.isNotRecordedScopeAvailable()
               && snapshot.getNotRecordedTables().equals(catalog.stream().sorted().collect(Collectors.toList())),
               "all catalog tables remain historically not recorded when every table is excluded");
         check(fixture.configuration.getFilter() == null, "empty scope does not modify the logger context");
      }
      try (Fixture fixture = new Fixture("no-excluded-scope", Level.ERROR)) {
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, oid(), TABLES, TABLES, SqlEvidence.Limits.defaults());
         window.stop();
         check(window.snapshot().isNotRecordedScopeAvailable() && window.snapshot().getNotRecordedTables().isEmpty(),
               "a known empty complement is distinguished from unavailable legacy metadata");
      }
      try (Fixture fixture = new Fixture("empty-catalog-scope", Level.ERROR)) {
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, oid(), List.of(), List.of(), SqlEvidence.Limits.defaults());
         check(window.snapshot().isNotRecordedScopeAvailable() && window.snapshot().getNotRecordedTables().isEmpty(),
               "even an explicitly empty Start-time catalog is preserved as known, not unavailable");
      }
      try (Fixture fixture = new Fixture("incomplete-catalog-scope", Level.ERROR)) {
         for (List<String> bad : List.of(List.of("WTPART"), List.of(""), Arrays.asList("WTPART", null))) {
            expect(IllegalArgumentException.class, () -> SqlEvidenceCapture.open(fixture.context,
                  ProfilerDiagnosticsTest::probe, fixture.store, oid(), TABLES, bad, SqlEvidence.Limits.defaults()));
         }
         check(!Files.exists(fixture.root) && fixture.configuration.getFilter() == null,
               "invalid/incomplete catalogs are rejected before evidence files or logging filters are changed");
      }
      try (Fixture fixture = new Fixture("unavailable-frozen-scope", Level.ERROR)) {
         AbstractFilter existing = new AbstractFilter() { };
         fixture.configuration.addFilter(existing);
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, oid(), TABLES, catalog, SqlEvidence.Limits.defaults());
         check(window.snapshot().getState() == SqlEvidence.State.UNAVAILABLE
               && window.snapshot().isNotRecordedScopeAvailable(),
               "the historical scope survives even when an existing filter prevents tracing");
         check(window.snapshot().getNotRecordedTables()
               .equals(List.of("DBCAPTURESESSION", "NO_ID_TABLE", "Quoted, Table", "WTPart")),
               "catalog names round-trip exactly, including commas and case-sensitive quoted names");
         expect(UnsupportedOperationException.class, () -> window.snapshot().getNotRecordedTables().add("NEW_TABLE"));
         check(fixture.configuration.getFilter() == existing, "unavailable tracing preserves the existing filter");
         Path file = sessionDirectory(fixture.root).resolve("metadata.properties");
         Properties original = metadata(file);
         for (Map<String, String> changes : List.of(
               Map.of("notRecordedTableCount", "-1"), Map.of("notRecordedTableCount", "32769"),
               Map.of("notRecordedTableCount", "invalid"), Map.of("notRecordedTable.0", ""),
               Map.of("notRecordedTable.0", "WTPART"),
               Map.of("notRecordedTable.0", "NO_ID_TABLE"))) {
            Properties broken = new Properties();
            broken.putAll(original);
            broken.putAll(changes);
            writeMetadata(file, broken);
            expect(IOException.class, window::snapshot);
         }
         Properties missing = new Properties();
         missing.putAll(original);
         missing.remove("notRecordedTable.0");
         writeMetadata(file, missing);
         expect(IOException.class, window::snapshot);
         writeMetadata(file, original);
         check(window.snapshot().getNotRecordedTables().size() == 4,
               "valid restored metadata still reads the exact historical complement");
      }
   }

   private static Properties metadata(Path file) throws IOException {
      Properties properties = new Properties();
      try (var input = Files.newInputStream(file)) {
         properties.load(input);
      }
      return properties;
   }

   private static void writeMetadata(Path file, Properties properties) throws IOException {
      try (var output = Files.newOutputStream(file, StandardOpenOption.TRUNCATE_EXISTING)) {
         properties.store(output, "Isolated historical scope fixture");
      }
   }

   private static void classify() throws Exception {
      SqlStatementClassifier parser = new SqlStatementClassifier(
            Arrays.asList("WTPART", "WTDOCUMENT", "EPMDOCUMENT", "WC.WTPART"));
      String[] messages = {
         "Insert Statement=INSERT INTO WTPart (ida2a2, name) VALUES (?, ?);Bind Parameters=[987654, sample]",
         "Update(Batch) Statement=UPDATE WTDOCUMENT SET title=? WHERE ida2a2=?;Bind Parameters=[[987654, 333333]]",
         "Delete Statement=DELETE FROM EPMDocument WHERE ida2a2 IN (?,?);Bind Parameters=[444444,555555]",
         "EXECUTE: MERGE INTO WTPART A USING WTPART B ON(A.ida2a2=B.ida2a2) WHEN MATCHED THEN UPDATE SET A.x=1",
         " /* native comment */ UPDATE /*+ index(a) */ \"WC\" . \"WTPART\" a SET x=123456789",
         "-- prefix\nDELETE FROM wc.WTPart WHERE label='IDA2A2=555555';Bind Parameters=[20260918]",
         "Insert=INSERT INTO WTPART (ida2a2) VALUES (?);Bind Parameters=[999999]",
         "Delete=DELETE  FROM WTPart  WHERE (idA2A2 IN (?,?));Bind Parameters=[123456,654321]",
         "Update=UPDATE WTPart SET markForDeleteA2=? WHERE (idA2A2=?);Bind Parameters=[1,123456]",
         "Delete=DELETE FROM WC.WTPART WHERE idA2A2=?"
      };
      for (String message : messages) {
         SqlEvidence.Statement result = parser.classify(message);
         check(result != null && result.getNativeMessage().equals(message), "Actual native message is retained unchanged: " + message);
      }
      String[] rejected = {
         "SQL Query:SELECT * FROM WTPART WHERE x='UPDATE WTPART SET x=2'",
         "SELECT * FROM WTPART", "WITH x AS (SELECT 1 FROM dual) UPDATE WTPART SET x=2",
         "INSERT ALL INTO WTPART VALUES (1) INTO WTDOCUMENT VALUES(2) SELECT 1 FROM dual",
         "BEGIN UPDATE WTPART SET x=4; END;", "UPDATE WTPART_OTHER SET x=1",
         "UPDATE \"WTPart\" SET x=1", "DELETE FROM WTPART@OTHER_DB",
         "UPDATE WC.WC.WTPART SET x=1", "Insert Statement=SELECT * FROM WTPART",
         "Update Statement=UPDATE DBCAPTURESESSION SET x=555555",
         "/* incomplete comment UPDATE WTPART SET x=5",
         "UPDATE \"WTPART\"\"OTHER\" SET x=1", "UPDATE OTHER_SCHEMA.WTPART SET x=1",
         "Delete=SELECT * FROM WTPART", "Delete=DELETE FROM DBCAPTURESESSION WHERE idA2A2=123456",
         "Delete=DELETE FROM OTHER_SCHEMA.WTPART WHERE idA2A2=123456",
         "Delete=DELETE FROM WTPART@OTHER_DB WHERE idA2A2=123456",
         "SQL Query:SELECT 'Delete=DELETE FROM WTPART' FROM DUAL",
         "Diagnostic Delete=DELETE FROM WTPART WHERE idA2A2=123456"
      };
      for (String message : rejected) {
         check(parser.classify(message) == null, "Unsupported/nonbusiness SQL must not be assigned a table: " + message);
      }
      expect(IllegalArgumentException.class, () -> new SqlStatementClassifier(Collections.singleton("DBCAPTURESESSION")));
      expect(IllegalArgumentException.class, () -> new SqlStatementClassifier(Collections.singleton("WTPART%")));
      expect(IllegalArgumentException.class, () -> new SqlStatementClassifier(Collections.emptyList()));
      expect(IllegalArgumentException.class, () -> SqlEvidence.Limits.forDuration(600_001));
      expect(IllegalArgumentException.class, () -> new SqlEvidence.Limits(1000, 0, 4096, 1, 256, 8));
   }

   private static void nativeBatchDml() throws Exception {
      // SQLDatabasePds.execute(WTConnection,String[],Object,Object,boolean,boolean,int)
      // logs these short prefixes at INFO immediately before executeUpdate(), not at DEBUG.
      String[] nativeMessages = {
         "Delete=DELETE  FROM WTPart  WHERE (idA2A2 IN (?,?));Bind Parameters=[123456,654321]",
         "Delete=DELETE  FROM WTPartMaster  WHERE (idA2A2=?);Bind Parameters=[234567]",
         "Update=UPDATE WTPartMaster SET markForDeleteA2=? WHERE (idA2A2=?);Bind Parameters=[1,234567]"
      };
      try (Fixture fixture = new Fixture("native-batch-dml", Level.ERROR)) {
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, oid(), Arrays.asList("WTPART", "WTPARTMASTER"), SqlEvidence.Limits.defaults());
         TestRequest request = new TestRequest("native-batch-request");
         inRequest(request, () -> {
            check(fixture.sql.isInfoEnabled() && !fixture.sql.isDebugEnabled(),
                  "Native batch DML needs only the existing scoped INFO observer");
            for (String message : nativeMessages) {
               nativeInsert(fixture.sql, message);
            }
            nativeInsert(fixture.sql, "Delete=DELETE FROM UnmonitoredObject WHERE idA2A2=?");
            nativeInsert(fixture.sql, "SQL Query:SELECT 'Delete=DELETE FROM WTPART' FROM DUAL");
         });
         check(window.snapshot().getEvents().isEmpty(), "Native batch SQL remains withheld until request completion");
         request.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
         window.stop();
         SqlEvidence.Snapshot result = window.snapshot();
         check(result.getState() == SqlEvidence.State.COMPLETE, "Native batch request completes normally");
         check(result.getObservedMessages() == 5 && result.getFilteredStatements() == 2,
               "Only genuine allowlisted short-prefix DML survives classification");
         check(result.getEvents().size() == 3, "Two native DELETE and one batch UPDATE message retained");
         String[] operations = { "DELETE", "DELETE", "UPDATE" };
         String[] tables = { "WTPART", "WTPARTMASTER", "WTPARTMASTER" };
         for (int i = 0; i < nativeMessages.length; i++) {
            SqlEvidence.Event event = result.getEvents().get(i);
            check(event.getStatement().getOperation().equals(operations[i])
                  && event.getStatement().getTable().equals(tables[i]), "Native DML target and operation are parsed");
            check(event.getStatement().getNativeMessage().equals(nativeMessages[i]),
                  "Native SQL and bind annotation are preserved verbatim, without identity inference");
            check(event.getServletRequestId().equals(request.id)
                  && event.getStack().stream().anyMatch(frame -> frame.contains("nativeBatchDml")),
                  "Native batch evidence preserves caller stack and actual request identity");
         }
         check(result.getFailedRequestStatements() == 0 && result.getUnfinishedRequestStatements() == 0
               && result.getLimitDiscardedStatements() == 0, "Eligible native batch evidence is not withheld");
         check(fixture.configuration.getFilter() == null && fixture.sql.getLevel() == Level.ERROR
               && fixture.appender.messages.isEmpty(), "Batch-format support changes no logger lifetime or routing");
      }
   }

   private static void filterAndLifetime() throws Exception {
      try (Fixture fixture = new Fixture("scope", Level.ERROR)) {
         LoggerConfig root = fixture.configuration.getRootLogger();
         Map<String, ?> originalLoggers = Map.copyOf(fixture.configuration.getLoggers());
         Map<String, ?> originalAppenders = Map.copyOf(root.getAppenders());
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         check(fixture.sql.getLevel() == Level.ERROR, "No SQL level is changed");
         TestRequest successful = new TestRequest("r-success");
         inRequest(successful, () -> {
            check(fixture.sql.isInfoEnabled() && !fixture.sql.isDebugEnabled(),
                  "Only native INFO SQL gate is enabled; unbounded DEBUG/bind materialization remains off");
            check(!fixture.other.isDebugEnabled(), "Unrelated logger remains unchanged");
            nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART (name) VALUES ('not PBO 99887766')");
            fixture.sql.info("Update Statement=UPDATE {} SET name=?", "WTDOCUMENT");
            nativeInsert(fixture.sql, "SQL Query:SELECT * FROM WTPART WHERE ida2a2=99887766");
            try (SqlEvidenceCapture.Suppression first = SqlEvidenceCapture.suppress();
                 SqlEvidenceCapture.Suppression second = SqlEvidenceCapture.suppress()) {
               check(!fixture.sql.isInfoEnabled(), "Nested suppression excludes collection work");
               nativeInsert(fixture.sql, "Delete Statement=DELETE FROM WTPART WHERE ida2a2=9");
            }
            check(fixture.sql.isInfoEnabled(), "Suppression is scoped and removed");
         });
         check(window.snapshot().getEvents().isEmpty(), "Pending request data must not reach disk");
         successful.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
         for (String uri : Arrays.asList("/Windchill/ptc1/DbCapture/diagnostics", "/Windchill/login")) {
            TestRequest excluded = new TestRequest("excluded-uri");
            excluded.uri = uri;
            inRequest(excluded, () -> check(!fixture.sql.isInfoEnabled(), "Own/authentication URI excluded"));
         }
         TestRequest ownQuery = new TestRequest("own-query");
         ownQuery.query = "oid=OR%3Acom.ptc.%2564bcapture.DbCaptureSession%3A1";
         inRequest(ownQuery, () -> check(!fixture.sql.isInfoEnabled(), "Encoded own popup query excluded"));
         TestRequest ownClass = new TestRequest("own-class");
         ownClass.target = "com.custom.dbcapture.StandardDbCaptureService";
         inRequest(ownClass, () -> check(!fixture.sql.isInfoEnabled(), "Own target class excluded"));
         TestRequest unauthenticated = new TestRequest("no-auth");
         unauthenticated.authenticated = false;
         inRequest(unauthenticated, () -> check(!fixture.sql.isInfoEnabled(), "No authentication means no capture"));
         TestRequest anonymous = new TestRequest("anonymous");
         anonymous.user = "anonymousUser";
         inRequest(anonymous, () -> check(!fixture.sql.isInfoEnabled(), "Anonymous principal excluded"));
         TestRequest noContext = new TestRequest("no-context");
         noContext.context = "";
         inRequest(noContext, () -> check(!fixture.sql.isInfoEnabled(), "Missing native context proof excluded"));
         TestRequest failed = new TestRequest("reused-ajp-thread-failed");
         inRequest(failed, () -> nativeInsert(fixture.sql, "Update Statement=UPDATE WTPART SET x=1"));
         failed.outcome.set(SqlEvidenceCapture.Outcome.REJECTED);
         TestRequest unfinished = new TestRequest("reused-ajp-thread-unfinished");
         inRequest(unfinished, () -> nativeInsert(fixture.sql, "Delete Statement=DELETE FROM WTPART WHERE x=1"));
         String priorName = Thread.currentThread().getName();
         REQUEST.set(new TestRequest("background"));
         try {
            for (String name : Arrays.asList("BackgroundMethodServer-queue-1", "http-nio-8080-exec-1")) {
               Thread.currentThread().setName(name);
               check(!fixture.sql.isInfoEnabled(), "Only AJP foreground requests are captured");
            }
         } finally {
            REQUEST.remove();
            Thread.currentThread().setName(priorName);
         }
         check(fixture.appender.messages.isEmpty(), "Scoped enabling does not leak newly enabled SQL to old appenders");
         window.stop();
         window.abort();
         SqlEvidence.Snapshot result = window.snapshot();
         check(result.getState() == SqlEvidence.State.COMPLETE, "Stop is durable and idempotent");
         check(result.getEvents().size() == 2, "Only successful allowlisted DML retained");
         check(result.getFailedRequestStatements() == 1 && result.getUnfinishedRequestStatements() == 1,
               "Later authentication failure and uncompleted request evidence withheld");
         check(result.getFilteredStatements() == 1, "Diagnostic SELECT filtered and counted");
         check(result.getEvents().get(0).getStatement().getNativeMessage().contains("99887766"),
               "Numeric literals preserved as text, never promoted to PBO identity");
         check(result.getEvents().get(0).getStack().stream().anyMatch(frame -> frame.contains("nativeInsert")),
               "Actual synchronous SQL caller stack captured, not timer/appender worker stack");
         check(result.getEvents().get(0).getServletRequestId().equals("r-success"), "Request id not reused with AJP thread");
         check(fixture.configuration.getFilter() == null, "Owned filter removed");
         check(root == fixture.configuration.getRootLogger() && root.getLevel() == Level.ERROR,
               "Exact root logger object and level preserved");
         check(originalLoggers.equals(fixture.configuration.getLoggers()), "No inherited logger promoted to explicit config");
         check(originalAppenders.equals(root.getAppenders()), "All pre-existing appenders preserved");
         check(!fixture.sql.isInfoEnabled(), "Native gates restored after Stop");
         try (SqlEvidenceCapture again = fixture.open(SqlEvidence.Limits.defaults())) {
            check(again.isActive(), "Registry allows a subsequent OID after exact cleanup");
         }
      }
   }

   private static void baselineAndUnavailable() throws Exception {
      try (Fixture fixture = new Fixture("baseline", Level.INFO)) {
         LoggerConfig specific = new LoggerConfig("wt.pom.sql", Level.INFO, false);
         Filter localFilter = new AbstractFilter() { };
         specific.addFilter(localFilter);
         specific.addAppender(fixture.appender, Level.ALL, null);
         fixture.configuration.addLogger("wt.pom.sql", specific);
         fixture.context.updateLoggers();
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         TestRequest request = new TestRequest("baseline");
         request.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
         inRequest(request, () -> nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)"));
         window.stop();
         check(fixture.appender.messages.size() == 1, "Originally enabled logs still reach original appender");
         check(fixture.configuration.getLoggers().get("wt.pom.sql") == specific
               && specific.getFilter() == localFilter && !specific.isAdditive()
               && specific.getExplicitLevel() == Level.INFO, "Explicit level/filter/additivity identity preserved");
      }
      try (Fixture fixture = new Fixture("existing-filter", Level.WARN)) {
         Filter first = new AbstractFilter() { };
         Filter second = new AbstractFilter() { };
         fixture.configuration.addFilter(first);
         fixture.configuration.addFilter(second);
         Filter composite = fixture.configuration.getFilter();
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         check(window.getState() == SqlEvidence.State.UNAVAILABLE, "Existing global filters cause transparent unavailable state");
         check(fixture.configuration.getFilter() == composite, "Exact existing composite filter retained untouched");
         check(window.snapshot().getReason().contains("existing global"), "Native interception limitations are persisted");
      }
      try (Fixture fixture = new Fixture("overlap", Level.ERROR)) {
         SqlEvidenceCapture first = fixture.open(SqlEvidence.Limits.defaults());
         Filter owned = fixture.configuration.getFilter();
         SqlEvidenceCapture second = fixture.open(SqlEvidence.Limits.defaults());
         check(second.getState() == SqlEvidence.State.UNAVAILABLE, "Overlapping windows rejected");
         check(first.isActive() && fixture.configuration.getFilter() == owned, "Rejected overlap leaves first window untouched");
         first.abort();
         check(first.snapshot().getState() == SqlEvidence.State.ABORTED, "Abort durable");
      }
      try (Fixture fixture = new Fixture("probe-error", Level.ERROR)) {
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context,
               () -> { throw new IllegalStateException("fixture"); }, fixture.store, oid(),
               TABLES, SqlEvidence.Limits.defaults());
         inRequest(new TestRequest("probe-error"), () -> fixture.sql.isInfoEnabled());
         check(window.getState() == SqlEvidence.State.ERROR && fixture.configuration.getFilter() == null,
               "Observation error cleans up without throwing into application code");
      }
      try (Fixture fixture = new Fixture("probe-assertion", Level.ERROR)) {
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context,
               () -> { throw new AssertionError("fixture"); }, fixture.store, oid(),
               TABLES, SqlEvidence.Limits.defaults());
         inRequest(new TestRequest("probe-assertion"), () -> fixture.sql.isInfoEnabled());
         check(window.getState() == SqlEvidence.State.ERROR && fixture.configuration.getFilter() == null,
               "Nonfatal Error also restores logging without interrupting application work");
      }
   }

   private static void qualifiedWindchillFilter() throws Exception {
      try (Fixture fixture = new Fixture("windchill-reflection-filter", Level.ERROR)) {
         Filter baseline = MarkerFilter.createFilter("ReflectionFilter", Filter.Result.ACCEPT,
               Filter.Result.NEUTRAL);
         fixture.configuration.addFilter(baseline);
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         check(window.isActive(), "Windchill ReflectionFilter can coexist with scoped SQL evidence");
         window.stop();
         check(fixture.configuration.getFilter() == baseline,
               "Windchill ReflectionFilter identity survives exact DB Ninja cleanup");
      }
   }

   private static void multipleContextsRejectTheWholeRequest() throws Exception {
      try (Fixture fixture = new Fixture("multiple-contexts", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         TestRequest first = new TestRequest("one-http-request");
         first.context = "method-context-1";
         TestRequest second = new TestRequest("one-http-request");
         second.context = "method-context-2";
         inRequest(first, () -> nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)"));
         inRequest(second, () -> nativeInsert(fixture.sql, "Update Statement=UPDATE WTPART SET x=?"));
         first.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
         second.outcome.set(SqlEvidenceCapture.Outcome.REJECTED);
         window.stop();
         check(window.snapshot().getEvents().isEmpty() && window.snapshot().getFailedRequestStatements() == 2,
               "A recorded error in any captured MethodContext rejects the whole HTTP request, including earlier SQL");
      }
   }

   private static void timeoutAndReconfiguration() throws Exception {
      try (Fixture fixture = new Fixture("timeout", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.forDuration(250));
         await(() -> !window.isActive(), "Bounded watchdog terminates abandoned capture");
         window.stop();
         check(window.snapshot().getState() == SqlEvidence.State.TIMED_OUT, "Timeout durable");
         check(fixture.configuration.getFilter() == null, "Timeout removes filter without level reset");
      }
      try (Fixture fixture = new Fixture("reconfigure", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         DefaultConfiguration next = new DefaultConfiguration();
         next.getRootLogger().setLevel(Level.FATAL);
         fixture.context.start(next);
         await(() -> !window.isActive(), "Configuration change cancels capture");
         check(window.snapshot().getState() == SqlEvidence.State.UNAVAILABLE, "Reconfiguration explicitly reported");
         check(next.getRootLogger().getLevel() == Level.FATAL && next.getFilter() == null,
               "New configuration remains unchanged");
         check(fixture.configuration.getFilter() == null, "Old owned filter detached");
      }
      try (Fixture fixture = new Fixture("new-filter", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         Filter other = new AbstractFilter() { };
         fixture.configuration.addFilter(other);
         await(() -> !window.isActive(), "Concurrent filter customization cancels capture");
         await(window::isClosed, "Concurrent filter customization cleanup completes");
         check(fixture.configuration.getFilter() == other, "Concurrent customization survives cleanup");
      }
   }

   private static void bounds() throws Exception {
      try (Fixture fixture = new Fixture("event-cap", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(new SqlEvidence.Limits(5000, 1, 65536, 8, 4096, 32));
         TestRequest request = new TestRequest("events");
         request.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
         inRequest(request, () -> {
            nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)");
            nativeInsert(fixture.sql, "Update Statement=UPDATE WTPART SET x=?");
         });
         check(window.getState() == SqlEvidence.State.LIMIT_REACHED, "Event cap stops tracing");
         window.stop();
         check(window.snapshot().getEvents().size() == 1 && window.snapshot().getLimitDiscardedStatements() == 1,
               "Cap has explicit retained and dropped counts");
         check(fixture.configuration.getFilter() == null, "Cap removes logging hook");
      }
      try (Fixture fixture = new Fixture("message-cap", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(new SqlEvidence.Limits(5000, 20, 65536, 8, 256, 32));
         inRequest(new TestRequest("oversize"), () -> nativeInsert(fixture.sql,
               "Insert Statement=INSERT INTO WTPART VALUES ('" + "x".repeat(300) + "')"));
         window.stop();
         check(window.snapshot().getState() == SqlEvidence.State.LIMIT_REACHED
               && window.snapshot().getEvents().isEmpty(), "Oversized SQL is withheld, never silently truncated");
      }
      try (Fixture fixture = new Fixture("bytes-cap", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(new SqlEvidence.Limits(5000, 20, 4096, 8, 8192, 8));
         inRequest(new TestRequest("bytes"), () -> nativeInsert(fixture.sql,
               "Insert Statement=INSERT INTO WTPART VALUES ('" + "x".repeat(4096) + "')"));
         window.stop();
         check(window.snapshot().getState() == SqlEvidence.State.LIMIT_REACHED
               && window.snapshot().getEvents().isEmpty(), "Encoded file and memory byte cap enforced");
      }
      try (Fixture fixture = new Fixture("requests-cap", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(new SqlEvidence.Limits(5000, 20, 65536, 1, 4096, 32));
         inRequest(new TestRequest("pending-1"), () -> nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)"));
         inRequest(new TestRequest("pending-2"), () -> nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)"));
         window.stop();
         SqlEvidence.Snapshot result = window.snapshot();
         check(result.getState() == SqlEvidence.State.LIMIT_REACHED && result.getEvents().isEmpty()
               && result.getUnfinishedRequestStatements() == 1, "Request cap withholds unclassified request data");
      }
      try (Fixture fixture = new Fixture("stack-cap", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(new SqlEvidence.Limits(5000, 20, 65536, 8, 4096, 8));
         TestRequest request = new TestRequest("stack");
         request.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
         inRequest(request, () -> nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)"));
         window.stop();
         SqlEvidence.Event event = window.snapshot().getEvents().get(0);
         check(event.getStack().size() == 8 && event.isStackTruncated(), "Stack bound explicitly marked");
      }
   }

   private static void concurrency() throws Exception {
      try (Fixture fixture = new Fixture("concurrent", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         ExecutorService executor = Executors.newFixedThreadPool(8);
         try {
            List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 80; i++) {
               final int id = i;
               tasks.add(() -> {
                  TestRequest request = new TestRequest("concurrent-" + id);
                  request.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
                  inRequest(request, () -> nativeInsert(fixture.sql, "Update Statement=UPDATE WTPART SET x=" + id));
                  return null;
               });
            }
            for (Future<Void> result : executor.invokeAll(tasks)) {
               result.get();
            }
         } finally {
            executor.shutdownNow();
            check(executor.awaitTermination(5, TimeUnit.SECONDS), "Fixture request threads terminate");
         }
         window.stop();
         List<SqlEvidence.Event> events = window.snapshot().getEvents();
         check(events.size() == 80, "Concurrent callers neither lose nor duplicate accepted evidence");
         for (int i = 0; i < events.size(); i++) {
            check(events.get(i).getSequence() == i + 1
                  && events.get(i).getServletRequestId().startsWith("concurrent-"),
                  "Unique ordered sequence and request identity preserved");
         }
      }
   }

   private static void checkpointReadConcurrency() throws Exception {
      List<String> catalog = new ArrayList<String>(TABLES);
      for (int i = 0; i < 2000; i++) {
         catalog.add("CHECKPOINT_CONCURRENCY_TABLE_WITH_A_LONG_NAME_" + i);
      }
      try (Fixture fixture = new Fixture("checkpoint-read-concurrency", Level.ERROR)) {
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, oid(), TABLES, catalog, SqlEvidence.Limits.defaults());
         AtomicReference<Throwable> readerFailure = new AtomicReference<Throwable>();
         ExecutorService reader = Executors.newSingleThreadExecutor();
         Future<?> readerTask = reader.submit(() -> {
            while (!window.isClosed()) {
               try {
                  fixture.store.read(window.getSessionOid());
               } catch (Throwable failure) {
                  readerFailure.compareAndSet(null, failure);
                  return null;
               }
            }
            return null;
         });
         try {
            Thread.sleep(800);
            window.stop();
            readerTask.get(5, TimeUnit.SECONDS);
            check(readerFailure.get() == null,
               "Concurrent reads survive repeated large metadata checkpoints: " + readerFailure.get());
            check(window.snapshot().getState() == SqlEvidence.State.COMPLETE,
                  "Repeated large metadata checkpoints finish normally");
         } finally {
            readerTask.cancel(true);
            reader.shutdownNow();
            check(reader.awaitTermination(5, TimeUnit.SECONDS),
                  "Concurrent evidence reader terminates");
         }
      }
   }

   private static void storeSafety() throws Exception {
      try (Fixture fixture = new Fixture("store", Level.ERROR)) {
         expect(IllegalArgumentException.class, () -> fixture.store.read("CAP-000001"));
         expect(IllegalArgumentException.class, () -> fixture.store.read("../../elsewhere"));
         String id = oid();
         check(fixture.store.read(id).getState() == SqlEvidence.State.UNAVAILABLE, "Missing file is unavailable, not empty success");
         SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, id, TABLES, SqlEvidence.Limits.defaults());
         expect(IOException.class, () -> fixture.store.delete(id));
         window.stop();
         check(fixture.store.read("OR:" + id).getSessionOid().equals(id), "OR prefix canonicalized to actual OID");
         expect(IOException.class, () -> SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
               fixture.store, id, TABLES, SqlEvidence.Limits.defaults()));
         try (Stream<Path> all = Files.walk(fixture.root)) {
            for (Path path : all.collect(Collectors.toList())) {
               checkPrivate(path);
            }

         }
         fixture.store.delete(id);
         check(fixture.store.read(id).getState() == SqlEvidence.State.UNAVAILABLE, "Deletion is keyed by exact session OID");
         SqlEvidenceCapture replacement = fixture.open(SqlEvidence.Limits.defaults());
         replacement.stop();
         check(!replacement.getSessionOid().equals(id) && replacement.snapshot().getEvents().isEmpty(),
               "A new session using a recycled display capture ID cannot inherit old OID evidence");
      }
      Path links = base.resolve("links");
      createPrivateDirectory(links);
      Files.createSymbolicLink(links.resolve("alias"), links.toAbsolutePath());
      expect(IOException.class, () -> new SessionEvidenceStore(links.resolve("alias")));
      Path unsafe = links.resolve("unsafe");
      createPrivateDirectory(unsafe);
      makeUnsafe(unsafe);
      expect(IOException.class, () -> new SessionEvidenceStore(unsafe));
      expect(IOException.class, () -> new SessionEvidenceStore(base.resolve("codebase/evidence")));

      try (Fixture fixture = new Fixture("corrupt", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         TestRequest request = new TestRequest("corrupt");
         request.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
         inRequest(request, () -> nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)"));
         window.stop();
         Path directory = sessionDirectory(fixture.root);
         Path events = directory.resolve("events.bin");
         byte[] original = Files.readAllBytes(events);
         try (FileChannel channel = FileChannel.open(events, StandardOpenOption.WRITE)) {
            channel.position(12L + window.getSessionOid().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            channel.write(ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).flip());
         }
         expect(IOException.class, window::snapshot);
         Files.write(events, original, StandardOpenOption.TRUNCATE_EXISTING);
         makeUnsafe(events);
         expect(IOException.class, window::snapshot);
         makePrivate(events, false);
         makeUnsafeReadable(events);
         expect(IOException.class, window::snapshot);
         makePrivate(events, false);
         Path data = directory.resolve("metadata.properties");
         byte[] metadata = Files.readAllBytes(data);
         Files.writeString(data, "x".repeat(40_000), StandardOpenOption.TRUNCATE_EXISTING);
         expect(IOException.class, window::snapshot);
         Files.write(data, metadata, StandardOpenOption.TRUNCATE_EXISTING);
         Files.delete(events);
         Files.createSymbolicLink(events, data.toAbsolutePath());
         expect(IOException.class, window::snapshot);
         Files.delete(events);
         Files.write(events, original, StandardOpenOption.CREATE_NEW);
         makePrivate(events, false);
      }
   }

   private static void blockedFinalizationDoesNotHoldRequests() throws Exception {
      for (boolean timeout : new boolean[] { false, true }) {
         CountDownLatch entered = new CountDownLatch(1);
         CountDownLatch release = new CountDownLatch(1);
         ExecutorService emitter = Executors.newSingleThreadExecutor();
         try (Fixture fixture = new Fixture(timeout ? "blocked-timeout" : "blocked-writer", Level.ERROR)) {
            SqlEvidenceCapture window = fixture.open(new SqlEvidence.Limits(timeout ? 750 : 5000,
                  1, 65536, 8, 4096, 32));
            TestRequest first = new TestRequest("blocked-completion");
            first.completion = () -> {
               entered.countDown();
               if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IOException("Fixture release not received");
               }
               return SqlEvidenceCapture.Outcome.SUCCEEDED;
            };
            try {
               inRequest(first, () -> nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)"));
               check(entered.await(3, TimeUnit.SECONDS), "Fixture finalizer is deliberately stalled");
               if (timeout) {
                  await(() -> !window.isActive(), "Independent deadline restores logging despite a stalled finalizer");
                  check(window.getState() == SqlEvidence.State.TIMED_OUT, "Stalled finalizer cannot extend capture duration");
               } else {
                  Future<Void> emitted = emitter.submit(() -> {
                     inRequest(new TestRequest("cap-while-stalled"), () ->
                           nativeInsert(fixture.sql, "Update Statement=UPDATE WTPART SET x=1"));
                     return null;
                  });
                  emitted.get(1, TimeUnit.SECONDS);
                  check(window.getState() == SqlEvidence.State.LIMIT_REACHED,
                        "Application SQL thread never waits for evidence finalization");
               }
               check(fixture.configuration.getFilter() == null && fixture.sql.getLevel() == Level.ERROR,
                     "Logging state restored before any stalled final I/O can finish");
            } finally {
               release.countDown();
               emitter.shutdownNow();
               emitter.awaitTermination(5, TimeUnit.SECONDS);
            }
            window.stop();
            check(window.isClosed() && window.snapshot().getEvents().size() == 1,
                  "Bounded final evidence is durable after the stalled fixture is released");
         } finally {
            release.countDown();
            emitter.shutdownNow();
         }
      }
   }

   private static void persistenceFailure() throws Exception {
      try (Fixture fixture = new Fixture("disk-error", Level.ERROR)) {
         SqlEvidenceCapture window = fixture.open(SqlEvidence.Limits.defaults());
         Path directory = sessionDirectory(fixture.root);
            makeReadOnly(directory);
         await(window::isClosed, "Persistence failure disables observation and releases its writer");
         check(window.getState() == SqlEvidence.State.ERROR && fixture.configuration.getFilter() == null,
               "Persistence failure restores logging before surfacing the error");
            makePrivate(directory, true);
         expect(IOException.class, window::stop);
         check(window.snapshot().getState() == SqlEvidence.State.INTERRUPTED,
               "Last checkpoint honestly marked interrupted when final status cannot be persisted");
      }
   }

   private static void quota() throws Exception {
      try (Fixture fixture = new Fixture("quota", Level.ERROR)) {
         SqlEvidence.Limits large = new SqlEvidence.Limits(1000, 1, SqlEvidence.Limits.MAX_BYTES, 1, 256, 8);
         List<String> sessions = new ArrayList<String>();
         for (int i = 0; i < 8; i++) {
            SqlEvidenceCapture window = fixture.open(large);
            window.stop();
            sessions.add(window.getSessionOid());
         }
         expect(IOException.class, () -> fixture.open(large));
         fixture.store.delete(sessions.get(0));
         SqlEvidenceCapture reclaimed = fixture.open(large);
         reclaimed.stop();
         check(reclaimed.snapshot().getState() == SqlEvidence.State.COMPLETE, "Deletion reclaims bounded store reservation");
      }
   }

   private static void processLifetime(String mode) throws Exception {
      Path root = base.resolve(mode.substring(2));
      String executable = WINDOWS ? "java.exe" : "java";
      Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-XX:-UsePerfData", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
            "-cp", System.getProperty("java.class.path"), ProfilerDiagnosticsTest.class.getName(), mode,
            root.toString()).inheritIO().start();
      boolean exited = process.waitFor(30, TimeUnit.SECONDS);
      if (!exited && process.isAlive()) {
         process.destroyForcibly();
         process.waitFor(5, TimeUnit.SECONDS);
      }
      check(exited, "Fixture child exits within bound: " + mode);
      check(process.exitValue() == 0, "Fixture child exit successful");
      SqlEvidence.Snapshot result = new SessionEvidenceStore(root).read("com.custom.dbcapture.DbCaptureSession:999999");
      check(result.getState() == SqlEvidence.State.INTERRUPTED && result.getEvents().size() == 1,
            "Shutdown/crash retains only durable completed-request evidence with explicit interrupted state: "
                  + mode + " " + result.getState() + " events=" + result.getEvents().size());
   }

   private static void child(String mode, Path root) throws Exception {
      Fixture fixture = new Fixture(root, Level.ERROR);
      SqlEvidenceCapture window = SqlEvidenceCapture.open(fixture.context, ProfilerDiagnosticsTest::probe,
            fixture.store, "com.custom.dbcapture.DbCaptureSession:999999", TABLES, SqlEvidence.Limits.defaults());
      TestRequest request = new TestRequest("child-request");
      request.outcome.set(SqlEvidenceCapture.Outcome.SUCCEEDED);
      inRequest(request, () -> nativeInsert(fixture.sql, "Insert Statement=INSERT INTO WTPART VALUES (?)"));
      await(() -> !window.snapshot().getEvents().isEmpty(), "Child checkpoint written");
      if (mode.equals("--child-crash")) {
         Runtime.getRuntime().halt(0);
      }
      System.exit(0);
   }

   private static SqlEvidenceCapture.RequestInfo probe() {
      TestRequest request = REQUEST.get();
      if (request == null) {
         return null;
      }
      Thread thread = Thread.currentThread();
      return new SqlEvidenceCapture.RequestInfo(thread.getId(), thread.getName(), request.id, request.context,
            request.user, "effective-user", request.uri, request.query, request.target, "save",
            request.authenticated, request.completion == null ? request.outcome::get : request.completion);
   }

   private static void inRequest(TestRequest request, Action action) throws Exception {
      String name = Thread.currentThread().getName();
      Thread.currentThread().setName("ajp-nio-127.0.0.1-8010-exec-" + Thread.currentThread().getId());
      REQUEST.set(request);
      try {
         action.run();
      } finally {
         REQUEST.remove();
         Thread.currentThread().setName(name);
      }
   }

   private static void nativeInsert(Logger logger, String actualMessage) {
      if (logger.isInfoEnabled()) {
         logger.info((Object) actualMessage);
      }
   }

   private static Path sessionDirectory(Path root) throws IOException {
      try (Stream<Path> directories = Files.list(root)) {
         return directories.filter(Files::isDirectory).findFirst().orElseThrow();
      }
   }

   private static void createPrivateDirectory(Path path) throws IOException {
      Files.createDirectory(path);
      makePrivate(path, true);
   }

   private static void makePrivate(Path path, boolean directory) throws IOException {
      if (!WINDOWS) {
         Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
            directory ? "rwx------" : "rw-------"));
         return;
      }
      AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
         LinkOption.NOFOLLOW_LINKS);
      if (view == null) throw new IOException("ACL view unavailable for NTFS fixture");
      UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
      AclEntry.Builder builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
         .setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class));
      if (directory) builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
      view.setAcl(List.of(builder.build()));
   }

   private static void makeUnsafe(Path path) throws IOException {
      if (!WINDOWS) {
         Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
            Files.isDirectory(path) ? "rwxr-xr-x" : "rw-r--r--"));
         return;
      }
      AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
         LinkOption.NOFOLLOW_LINKS);
      List<AclEntry> acl = new ArrayList<AclEntry>(view.getAcl());
      UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
      UserPrincipal unrelated = unrelatedPrincipal(path, owner);
      acl.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(unrelated)
         .setPermissions(WRITE_PERMISSIONS).build());
      view.setAcl(acl);
   }

   private static UserPrincipal unrelatedPrincipal(Path path, UserPrincipal owner) throws IOException {
      for (Path current = path.getParent(); current != null; current = current.getParent()) {
         AclFileAttributeView parentView = Files.getFileAttributeView(current, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
         if (parentView == null) continue;
         for (AclEntry entry : parentView.getAcl()) {
            String name = entry.principal().getName().toUpperCase(java.util.Locale.ROOT);
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                  && !name.contains("SYSTEM") && !name.contains("ADMINISTRATORS")) {
               return entry.principal();
            }
         }
      }
      for (String name : List.of("Everyone", "BUILTIN\\Users", "Users", "Guest")) {
         try {
            UserPrincipal principal = path.getFileSystem().getUserPrincipalLookupService()
               .lookupPrincipalByName(name);
            if (!principal.equals(owner)) return principal;
         } catch (java.nio.file.attribute.UserPrincipalNotFoundException ignored) {
            // Try the next provider-specific spelling.
         }
      }
      throw new IOException("No non-owner Windows principal is available for the ACL rejection fixture");
   }

   private static void makeUnsafeReadable(Path path) throws IOException {
      if (!WINDOWS) {
         Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
         return;
      }
      AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
         LinkOption.NOFOLLOW_LINKS);
      List<AclEntry> acl = new ArrayList<AclEntry>(view.getAcl());
      UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
      UserPrincipal unrelated = unrelatedPrincipal(path, owner);
      acl.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(unrelated)
         .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES)).build());
      view.setAcl(acl);
   }

   private static void makeReadOnly(Path path) throws IOException {
      if (!WINDOWS) {
         Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-x------"));
         return;
      }
      AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
         LinkOption.NOFOLLOW_LINKS);
      UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
      Set<AclEntryPermission> permissions = EnumSet.of(AclEntryPermission.READ_DATA,
         AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.EXECUTE,
         AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE);
      view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
         .setPermissions(permissions).setFlags(AclEntryFlag.FILE_INHERIT,
            AclEntryFlag.DIRECTORY_INHERIT).build()));
   }

   private static void checkPrivate(Path path) throws IOException {
      if (!WINDOWS) {
         check(Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString(
            Files.isDirectory(path) ? "rwx------" : "rw-------")), "All evidence files are private");
         return;
      }
      AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
         LinkOption.NOFOLLOW_LINKS);
      UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
      Set<UserPrincipal> allowed = new HashSet<UserPrincipal>();
      allowed.add(owner);
      for (String accountName : List.of("NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators")) {
         try {
            allowed.add(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(accountName));
         } catch (java.nio.file.attribute.UserPrincipalNotFoundException ignored) {
            // The runtime uses the same fail-closed principal resolution policy.
         }
      }
      for (AclEntry entry : view.getAcl()) {
         check(entry.type() != AclEntryType.ALLOW
            || entry.permissions().isEmpty()
            || allowed.contains(entry.principal()), "All evidence files have restricted NTFS access ACLs");
      }
   }

   private static String oid() {
      return "com.custom.dbcapture.DbCaptureSession:" + (++nextOid);
   }

   private static void await(Check condition, String label) throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (!condition.test() && System.nanoTime() < deadline) {
         Thread.sleep(20);
      }
      check(condition.test(), label);
   }

   private static void check(boolean result, String label) {
      checks++;
      if (!result) {
         throw new AssertionError(label);
      }
   }

   private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
      try {
         action.run();
      } catch (Throwable error) {
         check(type.isInstance(error), "Expected " + type.getSimpleName() + ", got " + error.getClass().getSimpleName());
         return;
      }
      throw new AssertionError("Expected " + type.getSimpleName());
   }

   private interface Action { void run() throws Exception; }
   private interface Check { boolean test() throws Exception; }

   private static final class TestRequest {
      private final String id;
      private String context = "native-method-context";
      private String user = "authenticated-user";
      private String uri = "/Windchill/ptc1/business/save";
      private String query;
      private String target = "wt.part.StandardWTPartService";
      private boolean authenticated = true;
      private SqlEvidenceCapture.Completion completion;
      private final AtomicReference<SqlEvidenceCapture.Outcome> outcome =
            new AtomicReference<SqlEvidenceCapture.Outcome>(SqlEvidenceCapture.Outcome.PENDING);

      private TestRequest(String id) { this.id = id; }
   }

   private static final class RecordingAppender extends AbstractAppender {
      private final List<String> messages = Collections.synchronizedList(new ArrayList<String>());

      private RecordingAppender() {
         super("fixture-only", null, null, true, Property.EMPTY_ARRAY);
      }

      @Override
      public void append(LogEvent event) {
         messages.add(event.getMessage().getFormattedMessage());
      }
   }

   private static final class Fixture implements AutoCloseable {
      private final Path root;
      private final SessionEvidenceStore store;
      private final LoggerContext context = new LoggerContext("isolated-profiler-fixture-" + oid());
      private final DefaultConfiguration configuration = new DefaultConfiguration();
      private final RecordingAppender appender = new RecordingAppender();
      private final Logger sql;
      private final Logger other;

      private Fixture(String name, Level baseline) throws IOException {
         this(base.resolve(name), baseline);
      }

      private Fixture(Path root, Level baseline) throws IOException {
         this.root = root;
         this.store = new SessionEvidenceStore(root);
         configuration.getRootLogger().setLevel(baseline);
         for (String name : new ArrayList<String>(configuration.getRootLogger().getAppenders().keySet())) {
            configuration.getRootLogger().removeAppender(name);
         }
         appender.start();
         configuration.addAppender(appender);
         configuration.getRootLogger().addAppender(appender, Level.ALL, null);
         context.start(configuration);
         sql = context.getLogger("wt.pom.sql");
         other = context.getLogger("unrelated.customer.customization");
      }

      private SqlEvidenceCapture open(SqlEvidence.Limits limits) throws IOException {
         return SqlEvidenceCapture.open(context, ProfilerDiagnosticsTest::probe, store, oid(), TABLES, limits);
      }

      @Override
      public void close() {
         context.stop();
      }
   }
}
