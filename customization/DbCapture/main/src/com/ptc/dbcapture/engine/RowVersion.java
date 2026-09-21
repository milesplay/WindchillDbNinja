package com.ptc.dbcapture.engine;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One version of one row as Flashback returns it, plus the Flashback pseudo
 * columns that say when and by which transaction it came to be.
 */
public final class RowVersion {

   /** VERSIONS_OPERATION values Oracle reports. */
   public static final String OP_INSERT = "I";
   public static final String OP_UPDATE = "U";
   public static final String OP_DELETE = "D";

   private final long rowId;
   private final String operation;
   private final Timestamp startTime;
   private final long startScn;
   private final String transactionId;
   private final Map<String, String> columns;

   RowVersion(long rowId, String operation, Timestamp startTime, long startScn,
              String transactionId, Map<String, String> columns) {
      this.rowId = rowId;
      this.operation = operation;
      this.startTime = startTime;
      this.startScn = startScn;
      this.transactionId = transactionId;
      this.columns = columns;
   }

   public long getRowId() {
      return rowId;
   }

   public String getOperation() {
      return operation;
   }

   public Timestamp getStartTime() {
      return startTime;
   }

   public long getStartScn() {
      return startScn;
   }

   public String getTransactionId() {
      return transactionId;
   }

   public Map<String, String> getColumns() {
      return columns;
   }

   public String get(String column) {
      return columns.get(column);
   }

   /**
    * Columns whose value differs between this version and {@code previous}.
    * Insertion order is preserved so the report reads in table column order.
    */
   public Map<String, String[]> diff(RowVersion previous) {
      Map<String, String[]> changes = new LinkedHashMap<String, String[]>();
      for (Map.Entry<String, String> entry : columns.entrySet()) {
         String column = entry.getKey();
         String now = entry.getValue();
         String before = previous == null ? null : previous.columns.get(column);
         if (!equal(before, now)) {
            changes.put(column, new String[] { before, now });
         }
      }
      return changes;
   }

   private static boolean equal(String a, String b) {
      return a == null ? b == null : a.equals(b);
   }
}
