package com.custom.dbcapture.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Everything a stop produced: the changes, plus what went wrong along the way. */
public final class CaptureResult {

   private final List<CapturedChange> changes = new ArrayList<CapturedChange>();
   private final List<String> warnings = new ArrayList<String>();
   private long endScn;
   private int tablesChanged;
   private int tablesExamined;
   private boolean snapshotFallback;
   private final Set<String> recoveredTables = new LinkedHashSet<>();
   private final Set<String> recoveryCodes = new LinkedHashSet<>();
   private final java.util.Map<String, Integer> hybridRecoveredTables =
         new java.util.LinkedHashMap<>();
   private final Set<String> hybridRecoveryCodes = new LinkedHashSet<>();
   private long recoveryStart;
   private long recoveryEnd;

   public boolean hasSnapshotFallback() {
      return snapshotFallback;
   }

   void markSnapshotFallback() {
      snapshotFallback = true;
   }

   void recordSnapshotRecovery(String table, int oracleCode, long startScn, long endScn) {
      snapshotFallback = true;
      recoveredTables.add(table);
      recoveryCodes.add(String.format(java.util.Locale.ROOT, "ORA-%05d", oracleCode));
      recoveryStart = startScn - 1;
      recoveryEnd = endScn;
   }

   void recordHybridRecovery(String table, int oracleCode, long startScn, long endScn,
                             int recoveredRows) {
      snapshotFallback = true;
      hybridRecoveredTables.put(table, Integer.valueOf(recoveredRows));
      hybridRecoveryCodes.add(String.format(java.util.Locale.ROOT, "ORA-%05d", oracleCode));
      recoveryStart = startScn - 1;
      recoveryEnd = endScn;
   }

   /** The exact upper SCN used by the collection queries. */
   public long getEndScn() {
      return endScn;
   }

   void setEndScn(long endScn) {
      this.endScn = endScn;
   }

   public List<CapturedChange> getChanges() {
      return changes;
   }

   public List<String> getWarnings() {
      List<String> result = new ArrayList<>(warnings);
      if (!hybridRecoveredTables.isEmpty()) {
         List<String> tables = new ArrayList<>();
         for (java.util.Map.Entry<String, Integer> entry : hybridRecoveredTables.entrySet()) {
            tables.add(entry.getKey() + " (" + entry.getValue() + " row(s))");
         }
         result.add("Endpoint-ID history recovery (" + String.join(", ", hybridRecoveryCodes)
               + ") for " + hybridRecoveredTables.size() + " table(s): "
               + String.join(", ", tables) + ".\n"
               + "Net-changed row IDs recovered by comparing AS OF SCN " + recoveryStart
               + " and " + recoveryEnd + "; ID-limited Version Query restored"
               + " change SCN/time and transaction ID for every endpoint-visible change.\n"
               + "Transient-only operations with no endpoint difference may still be unavailable.");
      }
      if (!recoveredTables.isEmpty()) {
         result.add("Snapshot recovery (" + String.join(", ", recoveryCodes) + ") for "
               + recoveredTables.size() + " table(s): " + String.join(", ", recoveredTables) + ".\n"
               + "Net changes recovered by comparing AS OF SCN " + recoveryStart + " and " + recoveryEnd
               + " (inclusive capture bounds). Intermediate changes, commit SCN/time and transaction ID"
               + " are unavailable.");
      }
      return result;
   }

   void addWarning(String warning) {
      warnings.add(warning);
   }

   public int getTablesChanged() {
      return tablesChanged;
   }

   void setTablesChanged(int tablesChanged) {
      this.tablesChanged = tablesChanged;
   }

   public int getTablesExamined() {
      return tablesExamined;
   }

   void setTablesExamined(int tablesExamined) {
      this.tablesExamined = tablesExamined;
   }

   /** Joined warnings, clipped to the datastore column width. */
   public String warningText(int limit) {
      List<String> allWarnings = getWarnings();
      if (allWarnings.isEmpty()) {
         return null;
      }
      StringBuilder sb = new StringBuilder();
      for (String w : allWarnings) {
         if (sb.length() > 0) {
            sb.append('\n');
         }
         sb.append(w);
         if (sb.length() >= limit) {
            break;
         }
      }
      String text = sb.toString();
      return text.length() > limit ? text.substring(0, limit - 3) + "..." : text;
   }
}
