package com.ptc.dbcapture.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ptc.dbcapture.DbCaptureChange;

/**
 * Folds the version stream of a table into one record per changed row.
 *
 * Flashback hands back every intermediate version. For troubleshooting what an
 * operation did, what matters is the net effect per row over the window plus
 * the operation that produced it, so consecutive versions of the same row are
 * collapsed: first-seen state against last-seen state.
 */
public final class ChangeReducer {

   /** Windchill marks most removals by flipping this flag instead of deleting. */
   private static final String MARK_FOR_DELETE = "MARKFORDELETEA2";
   private static final String CLASSNAME_COLUMN = "CLASSNAMEA2A2";

   /**
    * Columns that move on every write and say nothing about intent. Reporting
    * them would bury the columns the investigator is actually looking for.
    */
   private static final String[] NOISE_COLUMNS = {
      "UPDATESTAMPA2", "UPDATECOUNTA2", "MODIFYSTAMPA2"
   };

   private final IdentityResolver identityResolver;

   public ChangeReducer(IdentityResolver identityResolver) {
      this.identityResolver = identityResolver;
   }

   /** {@code versions} must be ordered by row id then start SCN. */
   public List<CapturedChange> reduce(String tableName, List<RowVersion> versions) {
      List<CapturedChange> out = new ArrayList<CapturedChange>();

      int i = 0;
      while (i < versions.size()) {
         long rowId = versions.get(i).getRowId();
         int end = i;
         while (end < versions.size() && versions.get(end).getRowId() == rowId) {
            end++;
         }
         CapturedChange change = reduceRow(tableName, versions.subList(i, end));
         if (change != null) {
            out.add(change);
         }
         i = end;
      }
      return out;
   }

   private CapturedChange reduceRow(String tableName, List<RowVersion> rowVersions) {
      if (!wasTouched(rowVersions)) {
         // Every version predates the window: the row merely existed, it did
         // not change. VERSIONS BETWEEN returns those too.
         return null;
      }

      RowVersion first = rowVersions.get(0);
      RowVersion last = rowVersions.get(rowVersions.size() - 1);
      if (RowVersion.OP_INSERT.equals(first.getOperation())
            && RowVersion.OP_DELETE.equals(last.getOperation())) {
         return null;
      }
      RowVersion before = priorState(rowVersions);
      if (before == null && !RowVersion.OP_INSERT.equals(first.getOperation())) {
         throw new IllegalArgumentException("Missing pre-window state for " + tableName
               + " IDA2A2=" + first.getRowId() + "; cannot determine the endpoint net change.");
      }
      RowVersion endpoint = RowVersion.OP_DELETE.equals(last.getOperation()) ? before : last;

      CapturedChange change = new CapturedChange(tableName, first.getRowId());
      change.setChangeTime(last.getStartTime() != null ? last.getStartTime()
                                                       : first.getStartTime());
      change.setChangeScn(last.getStartScn());
      change.setTransactionId(last.getTransactionId());
      change.setClassName(pickClassName(endpoint, first));
      change.setOperation(classify(rowVersions));

      if (DbCaptureChange.OP_CREATE.equals(change.getOperation())) {
         // Nothing existed before, so every non-null column is the new state.
         for (Map.Entry<String, String> entry : last.getColumns().entrySet()) {
            if (entry.getValue() != null && !isNoise(entry.getKey())) {
               change.addDelta(entry.getKey(), null, entry.getValue());
            }
         }
      } else if (DbCaptureChange.OP_DELETE.equals(change.getOperation())) {
         // Report what existed at Start, not values from an intermediate update.
         for (Map.Entry<String, String> entry : before.getColumns().entrySet()) {
            if (entry.getValue() != null && !isNoise(entry.getKey())) {
               change.addDelta(entry.getKey(), entry.getValue(), null);
            }
         }
      } else {
         Map<String, String[]> diff = last.diff(before);
         for (Map.Entry<String, String[]> entry : diff.entrySet()) {
            if (!isNoise(entry.getKey())) {
               change.addDelta(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            }
         }
      }

      // An update whose only movement was in the noise columns is not worth a row.
      if (change.getDeltas().isEmpty()
            && DbCaptureChange.OP_UPDATE.equals(change.getOperation())) {
         return null;
      }

      change.setObjectIdentity(identityResolver.resolve(
            change.getClassName(), change.getRowId(), endpoint.getColumns()));
      return change;
   }

   /**
    * Decides what the window did to this row.
    *
    * The subtle case is the logical delete: Windchill sets markForDeleteA2 with
    * an UPDATE, so a plain reading of VERSIONS_OPERATION calls that a modify.
    * DBDiff2 had the same blind spot from the other direction - it only noticed
    * removals when a row disappeared, so logical deletes showed up as
    * "Modified" and physical deletes of never-seen rows not at all.
    */
   private String classify(List<RowVersion> rowVersions) {
      RowVersion first = rowVersions.get(0);
      RowVersion last = rowVersions.get(rowVersions.size() - 1);

      if (RowVersion.OP_DELETE.equals(last.getOperation())) {
         return DbCaptureChange.OP_DELETE;
      }
      if (RowVersion.OP_INSERT.equals(first.getOperation())) {
         return DbCaptureChange.OP_CREATE;
      }
      if (becameMarkedForDelete(rowVersions)) {
         return DbCaptureChange.OP_LOGICAL_DELETE;
      }
      return DbCaptureChange.OP_UPDATE;
   }

   /** True when at least one version was produced inside the window. */
   private static boolean wasTouched(List<RowVersion> rowVersions) {
      for (RowVersion v : rowVersions) {
         if (v.getOperation() != null && !v.getOperation().isEmpty()) {
            return true;
         }
      }
      return false;
   }

   private boolean becameMarkedForDelete(List<RowVersion> rowVersions) {
      RowVersion before = priorState(rowVersions);
      RowVersion last = rowVersions.get(rowVersions.size() - 1);
      return isFalsey(before == null ? null : before.get(MARK_FOR_DELETE))
          && isTruthy(last.get(MARK_FOR_DELETE));
   }

   /**
    * The state the row was in before the window touched it.
    *
    * A null-operation version predates the window. If DELETE is the first
    * operation, its values also describe that original state (including the
    * synthetic DELETE used by endpoint comparison). An initial UPDATE without
    * its predecessor cannot supply old values and must not fabricate them.
    */
   private static RowVersion priorState(List<RowVersion> rowVersions) {
      RowVersion first = rowVersions.get(0);
      if (first.getOperation() == null || first.getOperation().isEmpty()
            || RowVersion.OP_DELETE.equals(first.getOperation())) {
         return first;
      }
      // The window starts with an operation, so nothing here predates it.
      return null;
   }

   private static String pickClassName(RowVersion last, RowVersion first) {
      String name = last.get(CLASSNAME_COLUMN);
      return name != null ? name : first.get(CLASSNAME_COLUMN);
   }

   private static boolean isNoise(String column) {
      for (String noise : NOISE_COLUMNS) {
         if (noise.equals(column)) {
            return true;
         }
      }
      return false;
   }

   private static boolean isTruthy(String value) {
      return value != null && !"0".equals(value.trim());
   }

   private static boolean isFalsey(String value) {
      return value == null || "0".equals(value.trim());
   }
}
