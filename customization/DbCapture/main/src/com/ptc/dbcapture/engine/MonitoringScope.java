package com.ptc.dbcapture.engine;

import com.ptc.dbcapture.DbCaptureSettings;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Audited exact capture defaults, intersected with the connected schema rather
 * than advertised as guessed table names. No business-row queries are needed.
 */
public final class MonitoringScope {
   /** Existing persisted table/column name fields; changing these requires a model migration. */
   public static final int CAPTURE_NAME_LIMIT = 40;

   private static final Map<String, Rule> DEFAULTS = defaults();

   private MonitoringScope() {
   }

   private record Rule(String reason, String source) {
   }

   private static Map<String, Rule> defaults() {
      Map<String, Rule> rules = new TreeMap<>();
      String foundation = "db/sql3/wnc/Foundation/";
      String telemetry = foundation + "nonmodeled/tables/";
      for (String table : List.of("CacheStatistics", "JmxNotifications",
            "Log4JavascriptEvents", "MethodContexts", "MethodContextStats",
            "MethodServerInfo", "MiscLogEvents", "MSHealthStats",
            "RawMethodContextStats", "RawServletRequestStats",
            "RemoteCacheServerCalls", "RequestHistograms", "RmiHistograms",
            "RmiPerfData", "SampledMethodContexts", "SampledServletRequests",
            "ServerManagerInfo", "ServletRequests", "ServletRequestStats",
            "ServletSessionStats", "SMHealthStats", "TopSQLStats", "UserAgentInfo")) {
         add(rules, table, "Windchill server telemetry, performance samples or diagnostic logging.",
               telemetry + table + ".sql");
      }
      add(rules, "HandshakeLocks", "Server-to-server handshake coordination locks.",
            telemetry + "HandshakeLocks.sql");
      add(rules, "RMIStubs", "RMI service registry leases and heartbeat timestamps.",
            telemetry + "RMIStubs.sql");

      for (String table : List.of("ProcessingQueue", "QueueEntry", "QueueLock",
            "ScheduleQueue", "ScheduleQueueEntry", "sch_entries")) {
         add(rules, table, "Background queue execution, scheduling or queue coordination state.",
               foundation + "wt/queue/create_" + table + "_Table.sql");
      }
      for (String table : List.of("PQStats", "SQStats")) {
         add(rules, table, "Background queue throughput and execution statistics.",
               foundation + "wt/queue/create_" + table + "_Table.sql");
      }
      for (String table : List.of("ScheduleItem", "ScheduleHistory", "PersistantArg", "PrimitiveArg")) {
         add(rules, table, "Scheduler invocation, arguments, next-run counters or execution history.",
               foundation + "wt/scheduler/create_" + table + "_Table.sql");
      }
      add(rules, "DeferredEventNotification", "Deferred notification delivery work queue.",
            foundation + "wt/notify/create_DeferredEventNotification_Table.sql");
      add(rules, "JobToQueueEntryLink", "Import worker job-to-queue-entry bookkeeping.",
            "db/sql3/wnc/ImportExport/com/ptc/windchill/ixb/importer/create_JobToQueueEntryLink_Table.sql");
      add(rules, "VariantsQueueEntryInfo", "Variant worker queue-entry bookkeeping.",
            "db/sql3/pdml/DDLBasic/com/ptc/wpcfg/variants/queue/create_VariantsQueueEntryInfo_Table.sql");
      for (String table : List.of("PageResults", "ExtendedPageResults", "PagingSession")) {
         add(rules, table, "Transient server-side query paging results or paging-session state.",
               foundation + "wt/fc/create_" + table + "_Table.sql");
      }
      add(rules, "CollectorCache", "Serialized server-side object-collector working cache.",
            "db/sql3/wnc/CoreHtmlComp/com/ptc/core/htmlcomp/collection/engine/create_CollectorCache_Table.sql");
      add(rules, "ShadowCache", "Derived shadow lookup cache, not the shadow business objects.",
            "db/sql3/wnc/Shadow/wt/shadow/create_ShadowCache_Table.sql");
      add(rules, "FvMountValidatorLock", "Background vault-mount validator coordination timestamp.",
            foundation + "wt/fv/create_FvMountValidatorLock_Table.sql");
      add(rules, "PreferenceInstance",
            "Persisted Windchill preference values and setting changes; excluded by default with per-table opt-in.",
            foundation + "wt/preference/create_PreferenceInstance_Table.sql");

      // Deliberately not defaults: Queue*EventInfo audit records; TaskEvent*,
      // RecentUpdate, IndexStatus and workflow history; ClientCacheState's CAD
      // downloaded/locallyModified state; business schedules and subscriptions.
      return Collections.unmodifiableMap(rules);
   }

   private static void add(Map<String, Rule> rules, String name, String reason, String source) {
      rules.put(name.toUpperCase(Locale.ROOT), new Rule(reason, source));
   }

   public static Set<String> defaultExcludedTables() {
      return DEFAULTS.keySet();
   }

   public static String defaultReason(String name) {
      Rule rule = name == null ? null : DEFAULTS.get(name.toUpperCase(Locale.ROOT));
      return rule == null ? null : rule.reason();
   }

   /** Local shipped Oracle DDL supporting each default, for audit/isolated tests. */
   public static String defaultSource(String name) {
      Rule rule = name == null ? null : DEFAULTS.get(name.toUpperCase(Locale.ROOT));
      return rule == null ? null : rule.source();
   }

   /**
    * Exact physical schema at this instant, including tables without IDA2A2.
    * Call once at Start on the service's existing connection and retain the
    * result with the frozen TableFilter; do not call from banner/status polls.
    * The caller owns the connection. This method neither commits nor writes.
    */
   public static Catalog readCatalog(Connection connection) throws SQLException {
      Map<String, Boolean> tables = new TreeMap<>();
      try (PreparedStatement query = connection.prepareStatement(
            "SELECT t.TABLE_NAME, CASE WHEN EXISTS (SELECT 1 FROM USER_TAB_COLUMNS c "
            + "WHERE c.TABLE_NAME=t.TABLE_NAME AND c.COLUMN_NAME='IDA2A2') "
            + "THEN 1 ELSE 0 END FROM USER_TABLES t ORDER BY t.TABLE_NAME")) {
         query.setQueryTimeout(30);
         query.setFetchSize(256);
         try (ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
               tables.put(rows.getString(1), rows.getInt(2) == 1);
            }
         }
      }
      return new Catalog(tables);
   }

   /** Immutable schema facts. Safe to retain with a capture, or construct in tests. */
   public static final class Catalog implements Serializable {
      private static final long serialVersionUID = 1L;
      private final Map<String, Boolean> tables;

      public Catalog(Map<String, Boolean> tables) {
         TreeMap<String, Boolean> copy = new TreeMap<>();
         tables.forEach((name, hasRowId) ->
               copy.put(Objects.requireNonNull(name), Objects.requireNonNull(hasRowId)));
         this.tables = Collections.unmodifiableMap(copy);
      }

      public List<String> getTableNames() {
         return List.copyOf(tables.keySet());
      }

      public boolean contains(String name) {
         return tables.containsKey(name);
      }

      public String captureLimitation(String name) {
         if (!contains(name)) {
            return "Not a physical table in the connected Windchill schema.";
         }
         if (!name.equals(name.toUpperCase(Locale.ROOT))) {
            return "Quoted mixed-case table names are not supported by the capture engine.";
         }
         String unsupported = TableFilter.unsupportedNameReason(name);
         if (unsupported != null) return unsupported;
         if (!tables.get(name)) {
            return "No IDA2A2 column: the row-difference engine cannot capture this table.";
         }
         return null;
      }

      /** Same IDA2A2 eligibility and frozen filter used by CaptureEngine. */
      public List<String> includedTables(TableFilter filter) {
         List<String> included = new ArrayList<>();
         for (String name : tables.keySet()) {
            if (captureLimitation(name) == null && filter.isIncluded(name)) included.add(name);
         }
         return List.copyOf(included);
      }

      /**
       * Exact not-recorded tables and explanations for Start-time diagnostics.
       * Includes hard/site exclusions and ineligible tables, not only defaults.
       */
      public Map<String, String> excludedReasons(TableFilter filter) {
         Map<String, String> reasons = new LinkedHashMap<>();
         for (String name : tables.keySet()) {
            String limitation = captureLimitation(name);
            String exclusion = filter.exclusionReason(name);
            if (limitation != null || exclusion != null) {
               reasons.put(name, joinReasons(exclusion, limitation));
            }
         }
         return Collections.unmodifiableMap(reasons);
      }
   }

   /** The dual-list view; all collections have stable, exact, physical names. */
   public static final class Selection implements Serializable {
      private static final long serialVersionUID = 1L;
      private final List<String> excluded;
      private final List<String> included;
      private final List<String> locked;
      private final Map<String, String> reasons;
      private final String notice;

      private Selection(List<String> excluded, List<String> included, List<String> locked,
            Map<String, String> reasons, String notice) {
         this.excluded = List.copyOf(excluded);
         this.included = List.copyOf(included);
         this.locked = List.copyOf(locked);
         this.reasons = Collections.unmodifiableMap(new LinkedHashMap<>(reasons));
         this.notice = notice;
      }

      public List<String> getExcluded() { return excluded; }
      public List<String> getIncluded() { return included; }
      public List<String> getLocked() { return locked; }
      public Map<String, String> getReasons() { return reasons; }
      public String getNotice() { return notice; }
   }

   public static Selection describe(Properties settings, String configuredExclusions, Catalog catalog) {
      TableFilter filter = new TableFilter(settings, configuredExclusions);
      List<String> excluded = new ArrayList<>();
      List<String> included = new ArrayList<>();
      List<String> locked = new ArrayList<>();
      Map<String, String> reasons = new LinkedHashMap<>();
      for (String name : catalog.getTableNames()) {
         String reason = defaultReason(name);
         String lock = joinReasons(filter.lockedReason(name), catalog.captureLimitation(name));
         if (lock != null) {
            locked.add(name);
            reasons.put(name, joinReasons(reason, lock));
         } else if (reason != null) {
            if (filter.isIncluded(name)) included.add(name);
            else excluded.add(name);
            reasons.put(name, reason);
         }
      }
      List<String> notices = new ArrayList<>();
      boolean exactSelection = settings.containsKey(DbCaptureSettings.INCLUDED_TABLES);
      boolean legacySettings = false;
      for (String key : List.of(DbCaptureSettings.INCLUDE_MONITORING,
            DbCaptureSettings.INCLUDE_QUEUE, DbCaptureSettings.INCLUDE_STATISTICS)) {
         legacySettings |= exactSelection ? settings.containsKey(key)
               : DbCaptureSettings.isEnabled(settings, key, false);
      }
      if (legacySettings) {
         notices.add(exactSelection
               ? "Legacy group settings are retained, but the saved per-table selection takes precedence."
               : "Legacy enabled groups are represented by their eligible exact tables on the right. "
                  + "Save migrates that selection; newly audited defaults remain excluded.");
      }
      if (!TableFilter.splitPatterns(settings.getProperty(DbCaptureSettings.EXTRA_EXCLUDES)).isEmpty()) {
         notices.add("Preserved extraExcludeTables: "
               + settings.getProperty(DbCaptureSettings.EXTRA_EXCLUDES)
               + ". Matching tables are locked and cannot be re-enabled here.");
      }
      if (!TableFilter.splitPatterns(configuredExclusions).isEmpty()) {
         notices.add("Preserved " + TableFilter.EXCLUDE_PROPERTY + ": " + configuredExclusions
               + ". Matching tables are locked and cannot be re-enabled here.");
      }
      List<String> absent = new ArrayList<>();
      for (String name : filter.getRequestedIncludes()) {
         if (!catalog.contains(name)) absent.add(name);
      }
      if (!absent.isEmpty()) {
         notices.add("Saved selections no longer present in this schema: " + String.join(", ", absent)
               + ". They are not offered; saving replaces the selection with the displayed right-hand list.");
      }
      return new Selection(excluded, included, locked, reasons, String.join(" ", notices));
   }

   /** Validate the whole desired right-hand list before the caller saves anything. */
   public static List<String> validateIncludes(String csv, Properties settings,
         String configuredExclusions, Catalog catalog) {
      if (csv == null) {
         throw new IllegalArgumentException("includedTables is required; use an empty value for no overrides.");
      }
      List<String> names = TableFilter.splitExactNames(csv);
      TableFilter existing = new TableFilter(settings, configuredExclusions);
      for (String name : names) {
         if (!catalog.contains(name)) {
            throw new IllegalArgumentException("Unknown physical table: " + name + ". Nothing was saved.");
         }
         String lock = joinReasons(existing.lockedReason(name), catalog.captureLimitation(name));
         if (lock != null) {
            throw new IllegalArgumentException("Cannot include " + name + ": " + lock + " Nothing was saved.");
         }
         if (defaultReason(name) == null) {
            throw new IllegalArgumentException(name
                  + " is not a default-excluded operational table. Nothing was saved.");
         }
      }
      return names;
   }

   private static String joinReasons(String first, String second) {
      if (first == null) return second;
      if (second == null || first.equals(second)) return first;
      return first + " " + second;
   }
}
