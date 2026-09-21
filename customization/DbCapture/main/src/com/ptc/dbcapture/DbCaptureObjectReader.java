package com.ptc.dbcapture;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import wt.fc.ObjectIdentifier;
import wt.fc.PersistenceHelper;
import wt.util.WTException;

/** Read-only current-row inspection, anchored to a persisted captured delta rather than a supplied SQL/table. */
public final class DbCaptureObjectReader {
   public static final int ROW_LIMIT = 100;
   public static final int VALUE_LIMIT = 2000;
   public record ChangedValue(String column, String before, String after, boolean truncated)
         implements java.io.Serializable { }
   public record Snapshot(String table, long rowId, String reference, Timestamp readAt,
                          List<String> columns, List<List<String>> rows, boolean truncated,
                          String operation, List<ChangedValue> changes)
         implements java.io.Serializable {
      public String operationClass() {
         if (DbCaptureChange.OP_CREATE.equals(operation)) return "object-created";
         if (DbCaptureChange.OP_UPDATE.equals(operation)) return "object-updated";
         if (DbCaptureChange.OP_DELETE.equals(operation) || DbCaptureChange.OP_LOGICAL_DELETE.equals(operation)) {
            return "object-deleted";
         }
         return "object-unknown";
      }

      public ChangedValue changeFor(String column) {
         for (ChangedValue change : changes) if (change.column().equalsIgnoreCase(column)) return change;
         return null;
      }
   }

   private DbCaptureObjectReader() {
   }

   public static Snapshot read(String deltaId) throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      long id = positiveId(deltaId);
      DbCaptureAttrDelta delta = (DbCaptureAttrDelta) PersistenceHelper.manager.refresh(
            ObjectIdentifier.newObjectIdentifier(DbCaptureAttrDelta.class, id));
      if (delta == null) throw new WTException("The captured object entry no longer exists.");
      try {
         Snapshot snapshot = read(DbCaptureJdbc.connection(), delta.getTableName(), delta.getTargetRowId(),
               delta.getClassName());
         List<DbCaptureAttrDelta> recorded = new ArrayList<>();
         if (DbCaptureChange.OP_UPDATE.equals(delta.getOperation())) {
            DbCaptureChange change = delta.getChange();
            if (change == null) throw new WTException("The captured parent change no longer exists.");
            wt.fc.QueryResult found = DbCaptureHelper.findDeltas(change);
            while (found.hasMoreElements()) recorded.add((DbCaptureAttrDelta) found.nextElement());
         }
         return withCapture(snapshot, delta.getOperation(), recorded);
      } catch (SQLException | IOException e) {
         throw new WTException(e, "Could not read the current captured object row: " + e.getMessage());
      }
   }

   public static long positiveId(String raw) {
      if (raw == null || !raw.matches("[0-9]{1,19}")) {
         throw new IllegalArgumentException("A positive captured entry ID is required.");
      }
      try {
         long id = Long.parseLong(raw);
         if (id <= 0) throw new IllegalArgumentException("The captured entry ID must be positive.");
         return id;
      } catch (NumberFormatException e) {
         throw new IllegalArgumentException("The captured entry ID is outside the supported range.", e);
      }
   }

   public static Snapshot read(Connection connection, String table, long rowId, String className)
         throws SQLException, IOException {
      if (table == null || !table.matches("[A-Za-z][A-Za-z0-9_$#]{0,127}") || rowId <= 0) {
         throw new IllegalArgumentException("Invalid captured table or IDA2A2.");
      }
      String name = table.toUpperCase(Locale.ROOT);
      String quoted = "\"" + name + "\"";
      String order = "\"IDA2A2\" DESC";
      List<String> columns = new ArrayList<>();
      try (PreparedStatement description = connection.prepareStatement(
            "SELECT * FROM " + quoted + " WHERE 1=0")) {
         description.setQueryTimeout(10);
         try (ResultSet result = description.executeQuery()) {
            ResultSetMetaData meta = result.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnName(i));
         }
      }
      if (!columns.contains("IDA2A2")) throw new SQLException("Captured table has no IDA2A2 column.");
      for (String candidate : List.of("MODIFYSTAMPA2", "UPDATESTAMPA2", "CREATESTAMPA2")) {
         if (columns.contains(candidate)) {
            order = "\"" + candidate + "\" DESC NULLS LAST, " + order;
            break;
         }
      }
      String sql = "SELECT * FROM " + quoted + " WHERE \"IDA2A2\" = ? ORDER BY "
            + order + " FETCH FIRST " + (ROW_LIMIT + 1) + " ROWS ONLY";
      List<List<String>> rows = new ArrayList<>();
      boolean truncated = false;
      Timestamp readAt = new Timestamp(System.currentTimeMillis());
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
         statement.setLong(1, rowId);
         statement.setQueryTimeout(10);
         statement.setFetchSize(10);
         statement.setMaxRows(ROW_LIMIT + 1);
         try (ResultSet result = statement.executeQuery()) {
            ResultSetMetaData meta = result.getMetaData();
            while (result.next()) {
               if (rows.size() == ROW_LIMIT) {
                  truncated = true;
                  break;
               }
               List<String> values = new ArrayList<>();
               for (int i = 1; i <= columns.size(); i++) values.add(value(result, meta.getColumnType(i), i));
               rows.add(java.util.Collections.unmodifiableList(values));
            }
         }
      }
      return new Snapshot(name, rowId, (className == null || className.isBlank() ? name : className)
            + ":" + rowId, readAt, List.copyOf(columns), List.copyOf(rows), truncated, null, List.of());
   }

   public static Snapshot withCapture(Snapshot snapshot, String operation, List<DbCaptureAttrDelta> deltas) {
      List<ChangedValue> changes = new ArrayList<>();
      if (DbCaptureChange.OP_UPDATE.equals(operation)) {
         for (DbCaptureAttrDelta delta : deltas) {
            if (!snapshot.table().equalsIgnoreCase(delta.getTableName())
                  || snapshot.rowId() != delta.getTargetRowId() || !operation.equals(delta.getOperation())) {
               throw new IllegalArgumentException("Captured comparison values do not belong to this object change.");
            }
            changes.add(new ChangedValue(delta.getColumnName(), preview(delta.getOldValue()),
                  preview(delta.getNewValue()), delta.isTruncated()
                        || tooLong(delta.getOldValue()) || tooLong(delta.getNewValue())));
         }
      }
      return new Snapshot(snapshot.table(), snapshot.rowId(), snapshot.reference(), snapshot.readAt(),
            snapshot.columns(), snapshot.rows(), snapshot.truncated(), operation, List.copyOf(changes));
   }

   public static String displayValue(String value) {
      return value == null ? "(null)" : value.isEmpty() ? "(empty)" : value;
   }

   private static boolean tooLong(String value) {
      return value != null && value.length() > VALUE_LIMIT;
   }

   private static String preview(String value) {
      return tooLong(value) ? value.substring(0, VALUE_LIMIT) : value;
   }

   private static String value(ResultSet result, int type, int index) throws SQLException, IOException {
      if (type == Types.BLOB) {
         java.sql.Blob blob = result.getBlob(index);
         if (blob == null) return null;
         try { return "[BLOB: " + blob.length() + " bytes; content not displayed]"; }
         finally { blob.free(); }
      }
      if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY) {
         try (java.io.InputStream stream = result.getBinaryStream(index)) {
            if (stream == null) return null;
            byte[] bytes = stream.readNBytes(VALUE_LIMIT + 1);
            return "[binary: " + (bytes.length > VALUE_LIMIT ? "more than " + VALUE_LIMIT : bytes.length)
                  + " bytes; content not displayed]";
         }
      }
      if (type == Types.CLOB || type == Types.NCLOB || type == Types.LONGVARCHAR
            || type == Types.LONGNVARCHAR) {
         try (Reader reader = result.getCharacterStream(index)) {
            if (reader == null) return null;
            char[] chars = new char[VALUE_LIMIT + 1];
            int count = 0, read;
            while (count < chars.length && (read = reader.read(chars, count, chars.length - count)) != -1) {
               count += read;
            }
            return new String(chars, 0, Math.min(count, VALUE_LIMIT))
                  + (count > VALUE_LIMIT ? "\n[value truncated]" : "");
         }
      }
      String text = result.getString(index);
      return text == null || text.length() <= VALUE_LIMIT ? text
            : text.substring(0, VALUE_LIMIT) + "\n[value truncated]";
   }
}
