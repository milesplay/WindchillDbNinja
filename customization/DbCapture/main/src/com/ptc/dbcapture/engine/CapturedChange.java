package com.ptc.dbcapture.engine;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * One changed row, already reduced to what gets stored: the operation, the
 * identifying columns, and the per column old/new pairs.
 *
 * Kept separate from the persistable classes so the collection logic can be
 * reasoned about (and tested) without a datastore.
 */
public final class CapturedChange {

   /** One column that moved. */
   public static final class Delta {
      private final String column;
      private final String oldValue;
      private final String newValue;

      Delta(String column, String oldValue, String newValue) {
         this.column = column;
         this.oldValue = oldValue;
         this.newValue = newValue;
      }

      public String getColumn() {
         return column;
      }

      public String getOldValue() {
         return oldValue;
      }

      public String getNewValue() {
         return newValue;
      }
   }

   private final String tableName;
   private String className;
   private String operation;
   private final long rowId;
   private String objectIdentity;
   private Timestamp changeTime;
   private long changeScn;
   private String transactionId;
   private CorrelationHit correlation;
   private final List<Delta> deltas = new ArrayList<Delta>();

   CapturedChange(String tableName, long rowId) {
      this.tableName = tableName;
      this.rowId = rowId;
   }

   public String getTableName() {
      return tableName;
   }

   public String getClassName() {
      return className;
   }

   void setClassName(String className) {
      this.className = className;
   }

   public String getOperation() {
      return operation;
   }

   void setOperation(String operation) {
      this.operation = operation;
   }

   public long getRowId() {
      return rowId;
   }

   public String getObjectIdentity() {
      return objectIdentity;
   }

   void setObjectIdentity(String objectIdentity) {
      this.objectIdentity = objectIdentity;
   }

   public Timestamp getChangeTime() {
      return changeTime;
   }

   void setChangeTime(Timestamp changeTime) {
      this.changeTime = changeTime;
   }

   public long getChangeScn() {
      return changeScn;
   }

   void setChangeScn(long changeScn) {
      this.changeScn = changeScn;
   }

   public String getTransactionId() {
      return transactionId;
   }

   void setTransactionId(String transactionId) {
      this.transactionId = transactionId;
   }

   /** What the log tables said caused this change; null when unmatched. */
   public CorrelationHit getCorrelation() {
      return correlation;
   }

   void setCorrelation(CorrelationHit correlation) {
      this.correlation = correlation;
   }

   public List<Delta> getDeltas() {
      return deltas;
   }

   void addDelta(String column, String oldValue, String newValue) {
      deltas.add(new Delta(column, oldValue, newValue));
   }

   /** Comma separated column names, for the searchable summary column. */
   public String changedColumnList() {
      StringBuilder sb = new StringBuilder();
      for (Delta d : deltas) {
         if (sb.length() > 0) {
            sb.append(", ");
         }
         sb.append(d.getColumn());
      }
      return sb.toString();
   }
}
