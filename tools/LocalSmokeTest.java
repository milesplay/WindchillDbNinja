package com.custom.dbcapture.engine;

import com.custom.dbcapture.DbCaptureSearch;
import com.custom.dbcapture.DbCaptureHelper;
import com.custom.dbcapture.DbCaptureSession;
import java.sql.Types;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

public final class LocalSmokeTest {
    private static int assertions;

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
        assertions++;
    }

    public static void main(String[] args) throws Exception {
        check(DbCaptureSearch.forKeyword("") == null, "empty search disables the filter");
        check(DbCaptureSearch.forKeyword("WT").matches("wtpart"), "keyword normalization");
        check(DbCaptureSearch.forKeyword("wt*").matches("wtpart"), "prefix wildcard");
        check(DbCaptureSearch.forKeyword("*part").matches("wtpart"), "suffix wildcard");
        check(!DbCaptureSearch.forKeyword("wt").matches("epmdocument"), "unmatched keyword");
        check(DbCaptureSearch.forKeyword("a.b").matches("prefix a.b suffix"), "literal dot");
        check(!DbCaptureSearch.forKeyword("a.b").matches("axb"), "dot is not a regex wildcard");
        String longKeyword = "long_keyword_".repeat(40);
        check(DbCaptureSearch.forKeyword(longKeyword).matches(longKeyword), "long keyword");

        CaptureResult result = new CaptureResult();
        result.setEndScn(9_876_543_210L);
        check(result.getEndScn() == 9_876_543_210L, "64-bit exact end SCN");
        check(result.getChanges().isEmpty(), "new result has no changes");
        check(!result.hasSnapshotFallback(), "new result is not downgraded");
        result.markSnapshotFallback();
        check(result.hasSnapshotFallback(), "snapshot fallback is tracked");
        CaptureResult grouped = new CaptureResult();
        for (int i = 0; i < 25; i++) grouped.recordSnapshotRecovery("BUSINESS_" + i, 1555, 100, 200);
        check(grouped.getWarnings().size() == 1 && grouped.warningText(4000).contains("25 table(s)")
                        && grouped.warningText(4000).contains("BUSINESS_24"),
                "repeated Oracle recoveries are consolidated without dropping affected tables");
        grouped.addWarning("FAILED_TABLE: No changes were recorded for this table.");
        check(grouped.warningText(4000).startsWith("FAILED_TABLE"),
                "real data loss is reported before repeated recoverable warnings");
        CaptureResult hybrid = new CaptureResult();
        hybrid.recordHybridRecovery("WTPARTMASTER", 1555, 100, 200, 3);
        check(hybrid.hasSnapshotFallback()
                        && hybrid.warningText(4000).contains("WTPARTMASTER (3 row(s))")
                        && hybrid.warningText(4000).contains("ID-limited Version Query"),
                "hybrid recovery records endpoint discovery, recovered row count and metadata source");

        DbCaptureSession session = DbCaptureSession.newDbCaptureSession();
        session.setCaptureId("CAP-TEST");
        session.setStatus(DbCaptureSession.STATUS_COMPLETED_WARNINGS);
        session.setWarnings("One table could not be reconstructed.");
        check(DbCaptureHelper.isVisible(session, false), "warned captures remain visible by default");
        check(DbCaptureHelper.completionMessage(session).contains("results may be partial"),
                "completion message discloses warnings");
        session.setStatus(DbCaptureSession.STATUS_COMPLETED);
        check(DbCaptureHelper.completionMessage(session).contains("Read Warnings"),
                "legacy completed records with warnings are not described as clean");
        session.setStatus(DbCaptureSession.STATUS_RUNNING);
        check(!DbCaptureHelper.isVisible(session, false), "running captures remain hidden by default");
        check(DbCaptureHelper.isVisible(session, true), "unfinished opt-in is preserved");
        check(DbCaptureSession.STATUS_COMPLETED_WARNINGS.length() <= 20
                        && DbCaptureSession.MODE_MIXED.length() <= 20,
                "new values fit existing schema widths");
        check(new TableFilter().isIncluded("WTPART") && new TableFilter().isIncluded("WTPARTMASTER"),
                "parts and masters remain in monitoring scope");
        boundedLobValues();
        activityTimestampUsesDatabaseClock();
        System.out.println("PASS: " + assertions + " local smoke assertions (no database writes).");
    }

    private static void activityTimestampUsesDatabaseClock() throws Exception {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        java.sql.Timestamp since = java.sql.Timestamp.from(java.time.Instant.parse("2026-09-18T12:00:00Z"));
        try {
            for (String zone : new String[]{"UTC", "Asia/Tokyo"}) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
                java.util.concurrent.atomic.AtomicReference<Object[]> binding = new java.util.concurrent.atomic.AtomicReference<>();
                java.sql.Statement clock = (java.sql.Statement) java.lang.reflect.Proxy.newProxyInstance(
                        LocalSmokeTest.class.getClassLoader(), new Class<?>[]{java.sql.Statement.class},
                        (proxy, method, args) -> {
                            if ("executeQuery".equals(method.getName())) return oneTextRow("+08:00");
                            if ("close".equals(method.getName())) return null;
                            throw new UnsupportedOperationException(method.getName());
                        });
                java.sql.PreparedStatement statement = (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                        LocalSmokeTest.class.getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                        (proxy, method, args) -> {
                            if ("setTimestamp".equals(method.getName())) { binding.set(args); return null; }
                            if ("executeQuery".equals(method.getName())) return oneTextRow("WTPART");
                            if ("close".equals(method.getName())) return null;
                            throw new UnsupportedOperationException(method.getName());
                        });
                java.sql.Connection connection = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                        LocalSmokeTest.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                        (proxy, method, args) -> {
                            if ("createStatement".equals(method.getName())) return clock;
                            if ("prepareStatement".equals(method.getName())) return statement;
                            throw new UnsupportedOperationException(method.getName());
                        });
                check(ActivitySnapshot.modifiedSince(connection, since,
                                new TableFilter(new java.util.Properties(), "")).equals(java.util.List.of("WTPART")),
                        "activity filtering is preserved in " + zone);
                Object[] args = binding.get();
                check(args != null && args.length == 3 && since.equals(args[1]),
                        "activity timestamp retains its instant and uses an explicit calendar in " + zone);
                java.util.Calendar calendar = (java.util.Calendar) args[2];
                check(calendar.getTimeZone().getOffset(since.getTime()) == 8 * 3600000,
                        "Oracle wall-clock cutoff is GMT+08, independent of the JVM timezone " + zone);
            }
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    private static CachedRowSet oneTextRow(String value) throws Exception {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(1);
        meta.setColumnName(1, "VALUE");
        meta.setColumnType(1, Types.VARCHAR);
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        rows.setMetaData(meta);
        rows.moveToInsertRow();
        rows.updateString(1, value);
        rows.insertRow();
        rows.moveToCurrentRow();
        rows.beforeFirst();
        return rows;
    }

    private static void boundedLobValues() throws Exception {
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        meta.setColumnCount(3);
        String[] names = {"IDA2A2", "TEXTVALUE", "BINARYVALUE"};
        int[] types = {Types.BIGINT, Types.CLOB, Types.BLOB};
        for (int i = 0; i < names.length; i++) {
            meta.setColumnName(i + 1, names[i]);
            meta.setColumnType(i + 1, types[i]);
        }
        try (CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet()) {
            rows.setMetaData(meta);
            rows.moveToInsertRow();
            rows.updateLong(1, 77L);
            rows.updateObject(2, new SerialClob("x".repeat(2000).toCharArray()));
            rows.updateObject(3, new SerialBlob(new byte[4096]));
            rows.insertRow();
            rows.moveToCurrentRow();
            rows.beforeFirst();
            check(rows.next(), "LOB fixture row exists");
            RowVersion row = FlashbackCollector.readRow(rows, meta, 1, null, null, 0, null);
            check(row.getRowId() == 77, "shared row reader preserves numeric ID");
            check(("x".repeat(1000) + "...").equals(row.get("TEXTVALUE")), "CLOB preview remains bounded");
            check("<binary 4096 bytes>".equals(row.get("BINARYVALUE")), "BLOB length marker is preserved");
        }
    }
}
