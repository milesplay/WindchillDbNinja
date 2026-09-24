package com.custom.dbcapture.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A reading of Oracle's per table DML counters, taken at capture start and again
 * at capture stop.
 *
 * This is what replaces DBDiff2's full scan of every table. DBDiff2 read
 * {@code IDA2A2, MODIFYSTAMPA2} out of some 1,300 tables twice per comparison;
 * here two reads of a small dictionary view narrow the work down to the handful
 * of tables that actually saw DML, and only those get examined row by row.
 */
public final class ActivitySnapshot {

   private static final String FLUSH_SQL =
      "BEGIN DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO; END;";

   private static final String READ_SQL =
      "SELECT TABLE_NAME, NVL(INSERTS,0), NVL(UPDATES,0), NVL(DELETES,0) "
    + "FROM USER_TAB_MODIFICATIONS";

   /**
    * The view also records when each table was last modified, which gives the
    * same answer without needing the start reading to still be in memory.
    * That matters because a capture may start on one method server and stop on
    * another.
    */
   private static final String MODIFIED_SINCE_SQL =
      "SELECT TABLE_NAME FROM USER_TAB_MODIFICATIONS WHERE TIMESTAMP >= ?";

   private final Map<String, TableActivity> byTable;

   private ActivitySnapshot(Map<String, TableActivity> byTable) {
      this.byTable = byTable;
   }

   /**
    * Flushes Oracle's in-memory monitoring info and reads the counters.
    *
    * The flush matters: without it the counters lag by up to about 15 minutes,
    * which is longer than a typical investigation window.
    */
   public static ActivitySnapshot take(Connection c) throws SQLException {
      flushMonitoringInfo(c);

      Map<String, TableActivity> map = new HashMap<String, TableActivity>();
      try (PreparedStatement ps = c.prepareStatement(READ_SQL);
           ResultSet rs = ps.executeQuery()) {
         while (rs.next()) {
            String table = rs.getString(1);
            map.put(table,
                    new TableActivity(table, rs.getLong(2), rs.getLong(3), rs.getLong(4)));
         }
      }
      return new ActivitySnapshot(map);
   }

   private static void flushMonitoringInfo(Connection c) throws SQLException {
      try (Statement s = c.createStatement()) {
         s.execute(FLUSH_SQL);
      } catch (SQLException e) {
         // A readable view after a failed flush is not fresh evidence. The
         // caller must examine its full scope rather than trust those counters.
         throw new SQLException("DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO failed: "
               + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
      }
   }

   /**
    * Tables Oracle says were modified at or after {@code since}.
    *
    * Stateless counterpart to {@link #changedTablesSince}; the two are unioned
    * so that neither a lost start reading nor a mid-window statistics gather
    * can hide a table.
    */
   public static List<String> modifiedSince(Connection c, java.sql.Timestamp since,
                                            TableFilter filter) throws SQLException {
      List<String> tables = new ArrayList<String>();
      java.util.Calendar databaseClock =
            java.util.Calendar.getInstance(DatabaseTime.readZone(c));
      try (PreparedStatement ps = c.prepareStatement(MODIFIED_SINCE_SQL)) {
         ps.setTimestamp(1, since, databaseClock);
         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               String name = rs.getString(1);
               if (filter.isIncluded(name)) {
                  tables.add(name);
               }
            }
         }
      }
      return tables;
   }

   /** Tables whose counters moved between {@code this} (start) and {@code later} (stop). */
   public List<String> changedTablesSince(ActivitySnapshot later, TableFilter filter) {
      List<String> changed = new ArrayList<String>();
      for (Map.Entry<String, TableActivity> entry : later.byTable.entrySet()) {
         String table = entry.getKey();
         if (!filter.isIncluded(table)) {
            continue;
         }
         if (entry.getValue().differsFrom(this.byTable.get(table))) {
            changed.add(entry.getValue().getTableName());
         }
      }
      // A table can also drop out of the view entirely when stats are gathered
      // mid-window. Anything that was moving at start and has since vanished
      // from the view still deserves a look.
      for (Map.Entry<String, TableActivity> entry : this.byTable.entrySet()) {
         String table = entry.getKey();
         if (!later.byTable.containsKey(table)
               && filter.isIncluded(table)
               && !changed.contains(entry.getValue().getTableName())) {
            changed.add(entry.getValue().getTableName());
         }
      }
      return changed;
   }

   public int size() {
      return byTable.size();
   }
}
