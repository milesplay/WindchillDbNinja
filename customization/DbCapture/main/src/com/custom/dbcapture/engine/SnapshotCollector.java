package com.custom.dbcapture.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Bounded-memory comparison when version history fails but endpoint snapshots survive. */
public final class SnapshotCollector {

   private final Connection connection;
   private final ChangeReducer reducer;

   public SnapshotCollector(Connection connection, ChangeReducer reducer) {
      this.connection = connection;
      this.reducer = reducer;
   }

   public Result collect(String table, long startScn, long endScn, int maxRows)
         throws SQLException {
      if (startScn <= 0 || endScn < startScn || maxRows <= 0) {
         throw new IllegalArgumentException("Invalid snapshot SCN bounds or row limit.");
      }

      String from = "SELECT t.* FROM " + FlashbackCollector.quote(table) + " AS OF SCN ";
      // VERSIONS BETWEEN includes a commit exactly at the lower bound.
      String beforeSql = from + (startScn - 1) + " t ORDER BY t.IDA2A2";
      String afterSql = from + endScn + " t ORDER BY t.IDA2A2";
      List<CapturedChange> changes = new ArrayList<CapturedChange>();
      boolean truncated = false;

      try (PreparedStatement beforeStatement = connection.prepareStatement(beforeSql);
           PreparedStatement afterStatement = connection.prepareStatement(afterSql)) {
         beforeStatement.setFetchSize(100);
         afterStatement.setFetchSize(100);
         try (ResultSet beforeRows = beforeStatement.executeQuery();
              ResultSet afterRows = afterStatement.executeQuery()) {
            ResultSetMetaData beforeMeta = beforeRows.getMetaData();
            ResultSetMetaData afterMeta = afterRows.getMetaData();
            RowVersion before = next(beforeRows, beforeMeta);
            RowVersion after = next(afterRows, afterMeta);

            while (before != null || after != null) {
               List<RowVersion> pair;
               if (after == null || before != null && before.getRowId() < after.getRowId()) {
                  pair = List.of(withOperation(before, RowVersion.OP_DELETE));
                  before = next(beforeRows, beforeMeta);
               } else if (before == null || after.getRowId() < before.getRowId()) {
                  pair = List.of(withOperation(after, RowVersion.OP_INSERT));
                  after = next(afterRows, afterMeta);
               } else {
                  pair = before.getColumns().equals(after.getColumns())
                        ? List.of()
                        : List.of(before, withOperation(after, RowVersion.OP_UPDATE));
                  before = next(beforeRows, beforeMeta);
                  after = next(afterRows, afterMeta);
               }

               for (CapturedChange change : reducer.reduce(table, pair)) {
                  if (changes.size() == maxRows) {
                     truncated = true;
                     break;
                  }
                  changes.add(change);
               }
               if (truncated) {
                  break;
               }
            }
         }
      }
      return new Result(changes, truncated);
   }

   private static RowVersion next(ResultSet rows, ResultSetMetaData meta) throws SQLException {
      return rows.next()
            ? FlashbackCollector.readRow(rows, meta, 1, null, null, 0L, null)
            : null;
   }

   private static RowVersion withOperation(RowVersion row, String operation) {
      // Endpoint comparison cannot recover a commit SCN, timestamp or transaction ID.
      return new RowVersion(row.getRowId(), operation, null, 0L, null, row.getColumns());
   }

   public static final class Result {
      private final List<CapturedChange> changes;
      private final boolean truncated;

      private Result(List<CapturedChange> changes, boolean truncated) {
         this.changes = changes;
         this.truncated = truncated;
      }

      public List<CapturedChange> getChanges() {
         return changes;
      }

      public boolean isTruncated() {
         return truncated;
      }
   }
}
