package com.ptc.dbcapture.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads what happened to one table between two SCNs, using Oracle's Flashback
 * Version Query.
 *
 * This is the piece DBDiff2 could not do. DBDiff2 compared two snapshots of
 * {@code IDA2A2, MODIFYSTAMPA2} and could therefore only say "this row is new
 * / changed / gone". Asking the undo stream for every version of every row in
 * the window gives the operation Oracle actually performed, the full column
 * values on both sides of an update, the contents of rows that were deleted,
 * and the transaction that did it.
 */
public final class FlashbackCollector {

   /** ORA-01555 / ORA-02063: undo for the requested window has been overwritten. */
   private static final int ORA_SNAPSHOT_TOO_OLD = 1555;
   /** ORA-30052: invalid lower limit snapshot expression. */
   private static final int ORA_INVALID_SNAPSHOT = 30052;
   /** ORA-01031: missing FLASHBACK privilege. */
   private static final int ORA_INSUFFICIENT_PRIVILEGES = 1031;
   /**
    * ORA-01466: the table's definition changed inside the window, so Oracle
    * will not reconstruct it. Seen for real against a table created moments
    * before the query - a DDL anywhere in the window disqualifies the table.
    */
   private static final int ORA_DEFINITION_CHANGED = 1466;

   /** Oracle's hard limit on entries in an IN list. */
   private static final int IN_LIST_LIMIT = 1000;

   /**
    * Two ways to read the current SCN, tried in order.
    *
    * DBMS_FLASHBACK is the documented call but needs EXECUTE granted, and
    * without it Oracle reports ORA-00904 "invalid identifier" rather than a
    * permission error, which is easy to misread. V$DATABASE.CURRENT_SCN needs
    * only SELECT on the view and gives the same number, so it serves as the
    * fallback - and on many sites it is the only one available.
    */
   private static final String[] CURRENT_SCN_SQL = {
      "SELECT DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER FROM DUAL",
      "SELECT CURRENT_SCN FROM V$DATABASE"
   };

   private final Connection connection;
   private java.util.TimeZone databaseZone;

   public FlashbackCollector(Connection connection) {
      this.connection = connection;
   }

   /** The SCN to anchor a capture window at. */
   public long currentScn() throws SQLException {
      SQLException last = null;
      for (String sql : CURRENT_SCN_SQL) {
         try (Statement s = connection.createStatement();
              ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) {
               return rs.getLong(1);
            }
         } catch (SQLException e) {
            last = e;
         }
      }
      throw new SQLException(
            "Could not read the current SCN. Grant one of these to the Windchill "
          + "schema user: EXECUTE ON DBMS_FLASHBACK, or SELECT ON SYS.V_$DATABASE. "
          + "Last error: " + (last == null ? "no rows returned" : last.getMessage()),
            last == null ? null : last.getSQLState(),
            last == null ? 0 : last.getErrorCode(), last);
   }

   /**
    * Row ids of {@code table} that were actually touched inside the window.
    *
    * This first pass matters more than it looks. A VERSIONS BETWEEN query
    * returns every row that existed at any point in the window, not just the
    * changed ones - on a table with 100,000 rows and one update that is
    * 100,001 rows to read and filter. Rows whose VERSIONS_OPERATION is null
    * were never touched, so asking Oracle for the distinct ids that do have an
    * operation narrows the second pass down to the handful that matter.
    */
   public List<Long> changedRowIds(String table, long startScn, long endScn)
         throws SQLException, UndoUnavailableException {

      String sql = "SELECT DISTINCT t.IDA2A2 FROM "
                 + versionSource(table, startScn, endScn)
                 + " t WHERE VERSIONS_OPERATION IS NOT NULL ORDER BY t.IDA2A2";

      List<Long> ids = new ArrayList<Long>();
      try (PreparedStatement ps = connection.prepareStatement(sql);
           ResultSet rs = ps.executeQuery()) {
         while (rs.next()) {
            ids.add(rs.getLong(1));
         }
      } catch (SQLException e) {
         throw translate(table, e);
      }
      return ids;
   }

   /**
    * Every version of the given rows inside the window, ordered by row then
    * SCN so the caller can walk consecutive versions and diff them.
    *
    * The pre-window version of a row comes back too (with a null operation),
    * which is exactly what supplies the "old" side of an update.
    */
   public List<RowVersion> versionsForRows(String table, long startScn, long endScn,
                                           List<Long> rowIds)
         throws SQLException, UndoUnavailableException {

      List<RowVersion> out = new ArrayList<RowVersion>();
      if (rowIds.isEmpty()) {
         return out;
      }
      // Oracle caps an IN list at 1000 entries.
      for (int from = 0; from < rowIds.size(); from += IN_LIST_LIMIT) {
         int to = Math.min(from + IN_LIST_LIMIT, rowIds.size());
         out.addAll(fetchChunk(table, startScn, endScn, rowIds.subList(from, to)));
      }
      return out;
   }

   private List<RowVersion> fetchChunk(String table, long startScn, long endScn,
                                       List<Long> chunk)
         throws SQLException, UndoUnavailableException {

      StringBuilder in = new StringBuilder();
      for (int i = 0; i < chunk.size(); i++) {
         in.append(i == 0 ? "?" : ",?");
      }
      String sql = "SELECT VERSIONS_XID, VERSIONS_OPERATION, VERSIONS_STARTSCN, "
                 + "VERSIONS_STARTTIME, t.* "
                 + "FROM " + versionSource(table, startScn, endScn) + " t "
                 + "WHERE t.IDA2A2 IN (" + in + ") "
                 + "ORDER BY t.IDA2A2, VERSIONS_STARTSCN NULLS FIRST";

      List<RowVersion> out = new ArrayList<RowVersion>();
      if (databaseZone == null) {
         databaseZone = DatabaseTime.readZone(connection);
      }
      try (PreparedStatement ps = connection.prepareStatement(sql)) {
         for (int i = 0; i < chunk.size(); i++) {
            ps.setLong(i + 1, chunk.get(i));
         }
         try (ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
               out.add(readRow(rs, meta, 5, trim(rs.getString(2)),
                     rs.getTimestamp(4, java.util.Calendar.getInstance(databaseZone)),
                     rs.getLong(3), toHex(rs.getBytes(1))));
            }
         }
      } catch (SQLException e) {
         throw translate(table, e);
      }
      return out;
   }

   private static String versionSource(String table, long startScn, long endScn) {
      // VERSIONS bounds do not pin the base snapshot. Both passes must read the saved Stop snapshot,
      // not reconstruct it from a later current read (which reproduced ORA-01555 on this Oracle).
      return quote(table) + " VERSIONS BETWEEN SCN " + startScn + " AND " + endScn
            + " AS OF SCN " + endScn;
   }

   static RowVersion readRow(ResultSet rs, ResultSetMetaData meta, int firstColumn,
                              String operation, Timestamp startTime, long startScn,
                              String transactionId)
         throws SQLException {
      Map<String, String> columns = new LinkedHashMap<String, String>();
      long rowId = 0L;
      boolean hasId = false;
      for (int i = firstColumn; i <= meta.getColumnCount(); i++) {
         String name = meta.getColumnName(i);
         if (name == null || name.isEmpty() || name.length() > MonitoringScope.CAPTURE_NAME_LIMIT) {
            throw new SQLException("Cannot capture column " + name + ": identifiers must fit the "
                  + MonitoringScope.CAPTURE_NAME_LIMIT + "-character persisted column name field.");
         }
         String value = readValue(rs, meta, i);
         columns.put(name, value);
         if ("IDA2A2".equals(name)) {
            rowId = rs.getLong(i);
            if (rs.wasNull()) {
               throw new SQLException("Cannot capture a row with a null IDA2A2.");
            }
            hasId = true;
         }
      }
      if (!hasId) {
         throw new SQLException("Cannot capture a table without IDA2A2.");
      }
      return new RowVersion(rowId, operation, startTime, startScn, transactionId, columns);
   }

   /**
    * Renders a column as text.
    *
    * LOBs are deliberately not materialised - a capture is about which columns
    * moved, and dragging content blobs through would blow up both memory and
    * the stored result. A marker keeps the change visible without the payload.
    */
   private static String readValue(ResultSet rs, ResultSetMetaData meta, int index)
         throws SQLException {
      int type = meta.getColumnType(index);
      switch (type) {
         case Types.BLOB:
            java.sql.Blob blob = rs.getBlob(index);
            if (blob == null) {
               return null;
            }
            try {
               return "<binary " + blob.length() + " bytes>";
            } finally {
               blob.free();
            }
         case Types.LONGVARBINARY:
         case Types.VARBINARY:
            byte[] raw = rs.getBytes(index);
            return raw == null ? null : "<binary " + raw.length + " bytes>";
         case Types.CLOB:
         case Types.NCLOB:
            java.sql.Clob clob = rs.getClob(index);
            if (clob == null) {
               return null;
            }
            try {
               long length = clob.length();
               String text = length == 0 ? "" : clob.getSubString(1, (int) Math.min(length, 1000));
               return length > 1000 ? text + "..." : text;
            } finally {
               clob.free();
            }
         default:
            return rs.getString(index);
      }
   }

   private static UndoUnavailableException translateOrNull(String table, SQLException e) {
      int code = e.getErrorCode();
      if (code == ORA_SNAPSHOT_TOO_OLD || code == ORA_INVALID_SNAPSHOT) {
         return new UndoUnavailableException(table,
            "Flashback version history is unavailable (ORA-" + code + "): "
                  + e.getMessage(), e);
      }
      if (code == ORA_INSUFFICIENT_PRIVILEGES) {
         return new UndoUnavailableException(table,
            "FLASHBACK privilege is missing (ORA-" + code + ")", e);
      }
      if (code == ORA_DEFINITION_CHANGED) {
         return new UndoUnavailableException(table,
            "the table was created or altered inside the capture window "
          + "(ORA-" + code + ")", e);
      }
      return null;
   }

   private static SQLException translate(String table, SQLException e)
         throws UndoUnavailableException {
      UndoUnavailableException translated = translateOrNull(table, e);
      if (translated != null) {
         throw translated;
      }
      return e;
   }

   /** Rejects anything that is not a plain identifier before it reaches SQL. */
   static String quote(String table) {
      String limitation = TableFilter.unsupportedNameReason(table);
      if (limitation != null || !table.equals(table.toUpperCase(java.util.Locale.ROOT))) {
         throw new IllegalArgumentException("Refusing unsupported physical table name: " + table
               + ". " + (limitation == null ? "Quoted mixed-case tables are not supported." : limitation));
      }
      return "\"" + table + "\"";
   }

   private static String trim(String s) {
      return s == null ? null : s.trim();
   }

   private static String toHex(byte[] bytes) {
      if (bytes == null) {
         return null;
      }
      StringBuilder sb = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
         sb.append(String.format("%02X", b & 0xFF));
      }
      return sb.toString();
   }

   /** Raised when the window cannot be reconstructed from undo. */
   public static final class UndoUnavailableException extends Exception {

      private static final long serialVersionUID = 1L;

      private final String tableName;

      UndoUnavailableException(String tableName, String message, Throwable cause) {
         super(tableName + ": " + message, cause);
         this.tableName = tableName;
      }

      public String getTableName() {
         return tableName;
      }

      public boolean canCompareSnapshots() {
         if (!(getCause() instanceof SQLException)) {
            return false;
         }
         int code = ((SQLException) getCause()).getErrorCode();
         return code == ORA_SNAPSHOT_TOO_OLD || code == ORA_INVALID_SNAPSHOT;
      }
   }
}
