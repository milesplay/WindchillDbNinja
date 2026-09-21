package com.ptc.dbcapture.engine;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import wt.util.WTProperties;

/**
 * Runs the collection half of a capture.
 *
 * The shape is DBDiff2's - compare two points in time - but the work in
 * between is different. DBDiff2 read {@code IDA2A2, MODIFYSTAMPA2} out of every
 * table on both sides and diffed the two maps in memory. Here the start side is
 * just an SCN plus Oracle's own DML counters, and at stop only the tables whose
 * counters moved get looked at, row by row, through the undo stream.
 */
public final class CaptureEngine {

   private static final org.apache.logging.log4j.Logger LOG =
         org.apache.logging.log4j.LogManager.getLogger(CaptureEngine.class);
   private static final String MAX_ROWS_PROPERTY = "com.ptc.dbcapture.maxRowsPerTable";
   private static final int MAX_ROWS_DEFAULT = 5000;

   private final Connection connection;
   private final TableFilter filter;
   private final FlashbackCollector flashback;
   private final ChangeReducer reducer;
   private final SnapshotCollector snapshots;
   private final LogCorrelator correlator;
   private final int maxRowsPerTable;

   public CaptureEngine(Connection connection) {
      this(connection, null);
   }

   /** Stop reuses the Start-time scope without reopening current settings. */
   public CaptureEngine(Connection connection, TableFilter scope) {
      this.connection = connection;
      this.filter = scope == null ? new TableFilter() : scope;
      this.flashback = new FlashbackCollector(connection);
      this.reducer = new ChangeReducer(new IdentityResolver());
      this.snapshots = new SnapshotCollector(connection, reducer);
      this.correlator = new LogCorrelator(connection);
      this.maxRowsPerTable = intProperty(MAX_ROWS_PROPERTY, MAX_ROWS_DEFAULT);
   }

   /**
    * Reads the current SCN, returning 0 rather than throwing.
    */
   public long currentScnQuietly() {
      try {
         return flashback.currentScn();
      } catch (SQLException e) {
         return 0L;
      }
   }

   /**
    * A baseline holding only an SCN, for a stop that cannot see the reading its
    * start took - typically a different method server in a cluster.
    */
   public static CaptureBaseline baselineFromScn(long scn, String warning) {
      return new CaptureBaseline(scn, null, warning);
   }

   /** The SCN, physical catalog and DML counters to compare against later. */
   public CaptureBaseline begin() throws SQLException {
      long scn = flashback.currentScn();
      MonitoringScope.Catalog catalog = MonitoringScope.readCatalog(connection);
      ActivitySnapshot activity;
      String warning = null;
      try {
         activity = ActivitySnapshot.take(connection);
      } catch (SQLException e) {
         // Without the counters every included table has to be examined at
         // stop; that is slower but still correct.
         activity = null;
         warning = "Could not read USER_TAB_MODIFICATIONS at start ("
               + e.getMessage() + "); every table will be examined at stop.";
      }
      return new CaptureBaseline(scn, activity, warning, filter, catalog);
   }

   /**
    * Collects everything that happened since {@code baseline}.
    *
    * A failure on one table is recorded as a warning and the rest continue -
    * DBDiff2 called {@code System.exit(1)} on the first SQLException, which
    * threw away the whole investigation.
    */
   public CaptureResult end(CaptureBaseline baseline, Timestamp startTime,
                            Timestamp endTime, boolean correlate) throws SQLException {

      CaptureResult result = new CaptureResult();
      if (baseline.getWarning() != null) {
         result.addWarning(baseline.getWarning());
      }

      long endScn = flashback.currentScn();
      result.setEndScn(endScn);
      List<String> candidates = candidateTables(baseline, startTime, result);
      result.setTablesExamined(candidates.size());

      int tablesWithChanges = 0;
      for (String table : candidates) {
         try {
            List<CapturedChange> changes =
                  collectTable(table, baseline.getScn(), endScn, result);
            if (changes.isEmpty()) {
               continue;
            }

            if (correlate) {
               applyCorrelation(table, changes, startTime, endTime);
            }
            result.getChanges().addAll(changes);
            tablesWithChanges++;
         } catch (FlashbackCollector.UndoUnavailableException e) {
            result.addWarning(e.getMessage());
         } catch (SQLException e) {
            result.addWarning(table + ": " + e.getMessage());
         } catch (RuntimeException e) {
            // A malformed table name, a type the driver cannot render, an
            // unexpected null - none of these are worth losing the rest of the
            // capture over. DBDiff2 called System.exit(1) on the first problem;
            // here the table is skipped and reported.
            result.addWarning(table + ": skipped - " + e);
         }
      }
      result.setTablesChanged(tablesWithChanges);
      return result;
   }

   private List<CapturedChange> collectTable(String table, long startScn, long endScn,
                                             CaptureResult result)
         throws SQLException, FlashbackCollector.UndoUnavailableException {
      try {
         try {
            return collectVersions(table, startScn, endScn, result);
         } catch (FlashbackCollector.UndoUnavailableException first) {
            if (!(first.getCause() instanceof SQLException)
                  || ((SQLException) first.getCause()).getErrorCode() != 1555) {
               throw first;
            }
            LOG.warn("DB Capture version read failed for {} at SCNs {}..{}; retrying once at the same fixed snapshot.",
                  table, startScn, endScn, first);
            return collectVersions(table, startScn, endScn, result);
         }
      } catch (FlashbackCollector.UndoUnavailableException unavailable) {
         if (!unavailable.canCompareSnapshots()) {
            throw unavailable;
         }
         SnapshotCollector.Result comparison;
         try {
            comparison = snapshots.collect(table, startScn, endScn, maxRowsPerTable);
         } catch (SQLException failure) {
            failure.addSuppressed(unavailable);
            throw new SQLException(unavailable.getMessage()
                  + "; endpoint snapshot comparison also failed: " + failure.getMessage()
                  + ". No changes were recorded for this table.",
                  failure.getSQLState(), failure.getErrorCode(), failure);
         }
         int oracleCode = ((SQLException) unavailable.getCause()).getErrorCode();
         if (!comparison.isTruncated() && !comparison.getChanges().isEmpty()) {
            try {
               List<Long> endpointIds = comparison.getChanges().stream()
                     .map(change -> Long.valueOf(change.getRowId())).toList();
               List<CapturedChange> recovered = reducer.reduce(table,
                     flashback.versionsForRows(table, startScn, endScn, endpointIds));
               if (sameEndpointEffect(comparison.getChanges(), recovered)
                     && recovered.stream().allMatch(change -> change.getChangeScn() > 0
                           && change.getChangeTime() != null
                           && change.getTransactionId() != null)) {
                  result.recordHybridRecovery(table, oracleCode, startScn, endScn,
                        recovered.size());
                  return recovered;
               }
               LOG.warn("DB Capture ID-limited history for {} at SCNs {}..{} did not "
                     + "match every endpoint difference with complete metadata; "
                     + "retaining endpoint comparison.", table, startScn, endScn);
            } catch (FlashbackCollector.UndoUnavailableException | SQLException | RuntimeException failure) {
               LOG.warn("DB Capture ID-limited history recovery failed for {} at SCNs "
                     + "{}..{}; retaining endpoint comparison.", table, startScn, endScn,
                     failure);
            }
         }
         result.recordSnapshotRecovery(table, oracleCode, startScn, endScn);
         if (comparison.isTruncated()) {
            result.addWarning(table + ": more than " + maxRowsPerTable
                  + " net changes; only the first " + maxRowsPerTable + " were recorded.");
         }
         return comparison.getChanges();
      }
   }

   private List<CapturedChange> collectVersions(String table, long startScn, long endScn,
                                                CaptureResult result)
         throws SQLException, FlashbackCollector.UndoUnavailableException {
      List<Long> rowIds = flashback.changedRowIds(table, startScn, endScn);
      int found = rowIds.size();
      if (found > maxRowsPerTable) rowIds = rowIds.subList(0, maxRowsPerTable);
      List<CapturedChange> changes = reducer.reduce(table,
            flashback.versionsForRows(table, startScn, endScn, rowIds));
      if (found > maxRowsPerTable) {
         result.addWarning(table + ": " + found + " changed rows exceeded the "
               + maxRowsPerTable + " row cap; only the first " + maxRowsPerTable + " were recorded.");
      }
      return changes;
   }

   private static boolean sameEndpointEffect(List<CapturedChange> expected,
                                             List<CapturedChange> actual) {
      if (expected.size() != actual.size()) {
         return false;
      }
      Map<Long, CapturedChange> byId = new LinkedHashMap<Long, CapturedChange>();
      for (CapturedChange change : actual) {
         if (byId.put(Long.valueOf(change.getRowId()), change) != null) {
            return false;
         }
      }
      for (CapturedChange endpoint : expected) {
         CapturedChange recovered = byId.get(Long.valueOf(endpoint.getRowId()));
         if (recovered == null || !Objects.equals(endpoint.getOperation(), recovered.getOperation())
               || !sameDeltas(endpoint.getDeltas(), recovered.getDeltas())) {
            return false;
         }
      }
      return true;
   }

   private static boolean sameDeltas(List<CapturedChange.Delta> expected,
                                     List<CapturedChange.Delta> actual) {
      if (expected.size() != actual.size()) {
         return false;
      }
      Map<String, CapturedChange.Delta> byColumn =
            new LinkedHashMap<String, CapturedChange.Delta>();
      for (CapturedChange.Delta delta : actual) {
         if (byColumn.put(delta.getColumn(), delta) != null) {
            return false;
         }
      }
      for (CapturedChange.Delta endpoint : expected) {
         CapturedChange.Delta recovered = byColumn.get(endpoint.getColumn());
         if (recovered == null
               || !Objects.equals(endpoint.getOldValue(), recovered.getOldValue())
               || !Objects.equals(endpoint.getNewValue(), recovered.getNewValue())) {
            return false;
         }
      }
      return true;
   }

   private void applyCorrelation(String table, List<CapturedChange> changes,
                                 Timestamp startTime, Timestamp endTime) {
      java.util.Map<Long, CorrelationHit> hits =
            correlator.correlate(table, startTime, endTime);
      if (hits.isEmpty()) {
         return;
      }
      for (CapturedChange change : changes) {
         CorrelationHit hit = hits.get(Long.valueOf(change.getRowId()));
         if (hit != null) {
            change.setCorrelation(hit);
         }
      }
   }

   /**
    * Tables worth examining.
    *
    * Counter differences and modification timestamps are unioned only after a
    * successful Stop flush/read. Without a Start reading or a fresh Stop
    * reading, stale or empty timestamps cannot establish that a table was
    * unchanged: examine the whole frozen physical scope instead.
    */
   private List<String> candidateTables(CaptureBaseline baseline, Timestamp startTime,
                                        CaptureResult result) throws SQLException {

      TableFilter filter = baseline.filter == null ? this.filter : baseline.filter;
      List<String> included = baseline.catalog == null ? allIncludedTables(filter)
            : baseline.catalog.includedTables(filter);
      if (baseline.getActivity() == null) {
         result.addWarning("Table activity could not be narrowed down; "
               + "examining every included table.");
         return included;
      }

      java.util.Set<String> candidates = new java.util.LinkedHashSet<String>();
      try {
         ActivitySnapshot now = ActivitySnapshot.take(connection);
         candidates.addAll(baseline.getActivity().changedTablesSince(now, filter));
      } catch (SQLException e) {
         result.addWarning("Could not re-read USER_TAB_MODIFICATIONS at stop ("
               + e.getMessage() + "); examining every included table.");
         return included;
      }

      if (startTime != null) {
         try {
            candidates.addAll(ActivitySnapshot.modifiedSince(connection, startTime, filter));
         } catch (SQLException e) {
            result.addWarning("Could not read modification timestamps ("
                  + e.getMessage() + ").");
         }
      }

      // DML monitoring also lists infrastructure tables with no Windchill row ID.
      candidates.retainAll(included);
      return new java.util.ArrayList<String>(candidates);
   }

   /** The same physical table scope is used for row differences and native request SQL. */
   public List<String> allIncludedTables(TableFilter filter) throws SQLException {
      return MonitoringScope.readCatalog(connection).includedTables(filter);
   }

   private static int intProperty(String name, int fallback) {
      try {
         String raw = WTProperties.getLocalProperties().getProperty(name, null);
         return raw == null ? fallback : Integer.parseInt(raw.trim());
      } catch (Exception e) {
         return fallback;
      }
   }

   /** What a start recorded, handed back to the matching stop. */
   public static final class CaptureBaseline {

      private final long scn;
      private final ActivitySnapshot activity;
      private final String warning;
      private final TableFilter filter;
      private final MonitoringScope.Catalog catalog;

      CaptureBaseline(long scn, ActivitySnapshot activity, String warning) {
         this(scn, activity, warning, null);
      }

      CaptureBaseline(long scn, ActivitySnapshot activity, String warning, TableFilter filter) {
         this(scn, activity, warning, filter, null);
      }

      CaptureBaseline(long scn, ActivitySnapshot activity, String warning, TableFilter filter,
                      MonitoringScope.Catalog catalog) {
         this.scn = scn;
         this.activity = activity;
         this.warning = warning;
         this.filter = filter;
         this.catalog = catalog;
      }

      public long getScn() {
         return scn;
      }

      public TableFilter getScope() {
         return filter;
      }

      public MonitoringScope.Catalog getCatalog() {
         return catalog;
      }

      ActivitySnapshot getActivity() {
         return activity;
      }

      String getWarning() {
         return warning;
      }
   }
}
