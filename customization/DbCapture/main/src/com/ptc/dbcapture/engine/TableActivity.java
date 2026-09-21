package com.ptc.dbcapture.engine;

/** Per table DML counters as Oracle reports them in USER_TAB_MODIFICATIONS. */
public final class TableActivity {

   private final String tableName;
   private final long inserts;
   private final long updates;
   private final long deletes;

   public TableActivity(String tableName, long inserts, long updates, long deletes) {
      this.tableName = tableName;
      this.inserts = inserts;
      this.updates = updates;
      this.deletes = deletes;
   }

   public String getTableName() {
      return tableName;
   }

   public long getInserts() {
      return inserts;
   }

   public long getUpdates() {
      return updates;
   }

   public long getDeletes() {
      return deletes;
   }

   public long total() {
      return inserts + updates + deletes;
   }

   /**
    * True when this reading shows activity beyond {@code baseline}.
    *
    * A counter that went *down* also counts as activity: Oracle resets these
    * counters when statistics are gathered on the table, so a decrease means
    * "reset happened, and we cannot prove nothing changed". Treating that as
    * changed keeps the detector from silently missing rows.
    */
   public boolean differsFrom(TableActivity baseline) {
      if (baseline == null) {
         return total() > 0;
      }
      return inserts != baseline.inserts
          || updates != baseline.updates
          || deletes != baseline.deletes;
   }
}
