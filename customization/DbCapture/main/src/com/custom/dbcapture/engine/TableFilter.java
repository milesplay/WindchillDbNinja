package com.custom.dbcapture.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import com.custom.dbcapture.DbCaptureSettings;

import wt.util.WTProperties;

/**
 * Decides which datastore tables a capture looks at.
 *
 * The operational defaults are audited exact names. Only those defaults may
 * be overridden; hard and site-specific exclusions always take precedence.
 * All settings are copied at construction so Start freezes the capture scope.
 */
public final class TableFilter {

   public static final String EXCLUDE_PROPERTY = "com.custom.dbcapture.excludeTables";

   /**
    * Oracle keeps dropped tables in the recycle bin under generated BIN$ names.
    * They appear in USER_TABLES and USER_TAB_MODIFICATIONS, hold no live data,
    * and are not plain identifiers - so they go before anything tries to quote
    * one into a query.
    */
   private static final String RECYCLE_BIN_PREFIX = "BIN$";

   /** Legacy/display patterns only; never applied as new capture defaults. */
   static final String[] MONITORING_TABLES = {
      "METHODCONTEXTS", "METHODSERVERINFO", "MISCLOGEVENTS", "SERVERMANAGERINFO",
      "SERVLETREQUESTS", "SAMPLEDMETHODCONTEXTS", "SAMPLEDSERVLETREQUESTS",
      "RMIPERFDATA", "REMOTECACHESERVERCALLS", "JMXNOTIFICATIONS",
      "LOG4JAVASCRIPTEVENTS", "MSHEALTHSTATS", "TOPSQLSTATS", "CACHESTATISTICS",
      "USERAGENTINFO"
   };

   /** Queues and scheduling: move without anyone touching the UI. */
   static final String[] QUEUE_TABLES = {
      "QUEUE*", "*QUEUEENTRY", "*QUEUEENTRYINFO", "PROCESSINGQUEUE",
      "SCHEDULEQUEUE*", "POLLINGQUEUEENTRY", "JOBTOQUEUEENTRYLINK", "DEFERREDEVENTNOTIFICATION"
   };

   /** Statistics, histograms and paging scratch. */
   static final String[] STATISTICS_TABLES = {
      "*STATS", "*HISTOGRAMS", "PAGERESULTS", "EXTENDEDPAGERESULTS",
      "TABLESTRUCTUREHASH", "COMPOSITEINDEXSTRUCTUREHASH"
   };

   /**
    * Groups the results page offers as display filters.
    *
    * These are tables that move without anyone touching the UI, so when the
    * question is "what did my click change?" their rows are noise. Filtering
    * here is display-only and reversible - the rows stay in the datastore, so
    * unticking a box brings them back without re-running the capture. That is
    * the difference from the Monitoring scope panel, which decides what a
    * capture collects in the first place and cannot be changed afterwards.
    *
    * Every pattern below was checked against the table names this release
    * actually ships (the create_*_Table.sql files under WT_HOME/db/sql3), so
    * they are real tables and not guesses. Add more with the free-text box on
    * the page rather than editing this list for a one-off investigation.
    */
   public static final class Group {

      public final String key;
      public final String label;
      public final String[] patterns;

      Group(String key, String label, String[] patterns) {
         this.key = key;
         this.label = label;
         this.patterns = patterns;
      }
   }

   public static final Group[] DISPLAY_GROUPS = {
      new Group("monitoring",
         "Monitoring and logging (MethodContexts, ServletRequests, ...)",
         MONITORING_TABLES),
      new Group("statistics",
         "Statistics, histograms and paging (*Stats, *Histograms, PageResults, ...)",
         new String[] {
            "*STATS", "*HISTOGRAMS", "PAGERESULTS", "EXTENDEDPAGERESULTS",
            "PAGINGSESSION", "TABLESTRUCTUREHASH", "COMPOSITEINDEXSTRUCTUREHASH"
         }),
      new Group("queue",
         "Queues and scheduling (QueueEntry, ProcessingQueue, QueueLock, ...)",
         new String[] {
            "QUEUE*", "*QUEUEENTRY", "*QUEUEENTRYINFO", "PROCESSINGQUEUE",
            "SCHEDULEQUEUE*", "JOBTOQUEUEENTRYLINK", "DEFERREDEVENTNOTIFICATION"
         }),
      new Group("taskEvents",
         "Task events (TaskEvent, TaskEventData, TaskEventMessage, ...)",
         new String[] { "TASKEVENT*" }),
      new Group("recentUpdates",
         "Recent updates (RecentUpdate)",
         new String[] { "RECENTUPDATE*" }),
      new Group("caches",
         "Server-side caches (ClientCacheState, ShadowCache, CollectorCache)",
         new String[] { "CLIENTCACHESTATE", "SHADOWCACHE", "COLLECTORCACHE" })
   };

   /** True when the name matches any of the patterns, wildcards included. */
   public static boolean matchesAny(String tableName, List<String> patterns) {
      if (tableName == null || patterns == null) {
         return false;
      }
      String name = tableName.toUpperCase(Locale.ROOT);
      for (String pattern : patterns) {
         if (pattern != null && matches(name, pattern.trim().toUpperCase(Locale.ROOT))) {
            return true;
         }
      }
      return false;
   }

   /**
    * Patterns of the named groups, for the display filter.
    *
    * The comparison is deliberately case insensitive. Group keys are camel
    * case ("taskEvents"), and the obvious way to read them off the request -
    * splitPatterns - upper-cases every token because that is right for table
    * names. Routed through that, no key ever equalled its Group and hiding a
    * group silently did nothing at all. Use splitKeys for this, and let the
    * comparison here forgive the mistake if anyone routes it the other way
    * again.
    */
   public static List<String> patternsForGroups(List<String> keys) {
      List<String> out = new ArrayList<String>();
      if (keys == null) {
         return out;
      }
      for (Group group : DISPLAY_GROUPS) {
         for (String key : keys) {
            if (key != null && key.equalsIgnoreCase(group.key)) {
               out.addAll(Arrays.asList(group.patterns));
               break;
            }
         }
      }
      return out;
   }

   /** Splits a comma separated, upper-cased pattern list. Public for the page. */
   public static List<String> splitPatterns(String raw) {
      return split(raw);
   }

   /**
    * Splits a comma separated list of DISPLAY_GROUPS keys, preserving case.
    * The counterpart of splitPatterns for things that are not table names.
    */
   public static List<String> splitKeys(String raw) {
      List<String> out = new ArrayList<String>();
      if (raw == null) {
         return out;
      }
      for (String token : raw.split(",")) {
         String trimmed = token.trim();
         if (!trimmed.isEmpty()) {
            out.add(trimmed);
         }
      }
      return out;
   }

   /**
    * The table names, out of the ones actually captured, that a display filter
    * would hide.
    *
    * The page needs to show what it is hiding as concrete names rather than as
    * patterns, and "resolve the patterns" must be the same code that decides
    * which rows to drop - otherwise the read-back reassures the reader about
    * something other than what happened.
    */
   public static List<String> resolveHidden(Collection<String> knownTables,
                                            List<String> patterns) {
      List<String> out = new ArrayList<String>();
      if (knownTables == null || patterns == null || patterns.isEmpty()) {
         return out;
      }
      for (String table : knownTables) {
         if (matchesAny(table, patterns)) {
            out.add(table);
         }
      }
      return out;
   }

   /** Always out: upgrade scaffolding and this feature's own tables. */
   private static final String[] ALWAYS_EXCLUDED = {
      "WTUPGINST*", "WCTK_*",
      "DBCAPTURESESSION", "DBCAPTURECHANGE", "DBCAPTURETABLECHANGE",
      "DBCAPTUREATTRDELTA", "DBCAPTURESQLEVENTS"
   };

   private final List<String> patterns;
   private final List<String> extraExclusions;
   private final List<String> configuredExclusions;
   private final Set<String> requestedIncludes;

   public TableFilter() {
      this(DbCaptureSettings.load(), configuredExclusions());
   }

   public TableFilter(Properties settings, String configuredExclusions) {
      Set<String> merged = new LinkedHashSet<String>();
      merged.addAll(Arrays.asList(ALWAYS_EXCLUDED));
      Set<String> includes = new LinkedHashSet<>();
      if (settings.containsKey(DbCaptureSettings.INCLUDED_TABLES)) {
         includes.addAll(splitExactNames(settings.getProperty(DbCaptureSettings.INCLUDED_TABLES)));
         for (String name : includes) {
            if (MonitoringScope.defaultReason(name) == null) {
               throw new IllegalArgumentException("Not an overridable operational table: " + name);
            }
         }
      } else {
         boolean monitoring = DbCaptureSettings.isEnabled(
               settings, DbCaptureSettings.INCLUDE_MONITORING, false);
         boolean queue = DbCaptureSettings.isEnabled(settings, DbCaptureSettings.INCLUDE_QUEUE, false);
         boolean statistics = DbCaptureSettings.isEnabled(
               settings, DbCaptureSettings.INCLUDE_STATISTICS, false);
         for (String name : MonitoringScope.defaultExcludedTables()) {
            boolean wasMonitoring = matchesAny(name, Arrays.asList(MONITORING_TABLES));
            boolean wasQueue = matchesAny(name, Arrays.asList(QUEUE_TABLES));
            boolean wasStatistics = matchesAny(name, Arrays.asList(STATISTICS_TABLES));
            if ((wasMonitoring || wasQueue || wasStatistics)
                  && (!wasMonitoring || monitoring) && (!wasQueue || queue)
                  && (!wasStatistics || statistics)) {
               includes.add(name);
            }
         }
      }
      this.requestedIncludes = Collections.unmodifiableSet(includes);
      for (String name : MonitoringScope.defaultExcludedTables()) {
         if (!includes.contains(name)) merged.add(name);
      }
      this.extraExclusions = List.copyOf(split(settings.getProperty(DbCaptureSettings.EXTRA_EXCLUDES)));
      this.configuredExclusions = List.copyOf(split(configuredExclusions));
      merged.addAll(this.extraExclusions);
      merged.addAll(this.configuredExclusions);
      this.patterns = Collections.unmodifiableList(new ArrayList<String>(merged));
   }

   public static String configuredExclusions() {
      try {
         return WTProperties.getLocalProperties().getProperty(EXCLUDE_PROPERTY, "");
      } catch (java.io.IOException e) {
         throw new java.io.UncheckedIOException("Could not read configured monitoring exclusions.", e);
      }
   }

   /** Exact identifiers for capture opt-ins, not the display filter's patterns. */
   public static List<String> splitExactNames(String raw) {
      Set<String> names = new java.util.TreeSet<>();
      if (raw != null) {
         for (String token : raw.split(",", -1)) {
            String name = token.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (!name.matches("[A-Z0-9_$#]{1,128}")) {
               throw new IllegalArgumentException("Invalid exact table name: " + token.trim()
                     + ". Wildcards, qualified names and quoted names are not allowed.");
            }
            names.add(name);
         }
      }
      return List.copyOf(names);
   }

   private static List<String> split(String raw) {
      List<String> out = new ArrayList<String>();
      if (raw == null) {
         return out;
      }
      for (String token : raw.split(",")) {
         String trimmed = token.trim();
         if (!trimmed.isEmpty()) {
            if (!trimmed.matches("\\*|\\*?[A-Za-z0-9_$#]+\\*?")) {
               throw new IllegalArgumentException("Invalid table pattern: " + trimmed
                     + ". Use a table name, PREFIX*, *SUFFIX, *TEXT* or *.");
            }
            String normalized = trimmed.toUpperCase(Locale.ROOT);
            if (!out.contains(normalized)) out.add(normalized);
         }
      }
      return out;
   }

   /** True when the table should take part in a capture. */
   public boolean isIncluded(String tableName) {
      return exclusionReason(tableName) == null;
   }

   public static String unsupportedNameReason(String tableName) {
      if (tableName == null || tableName.isEmpty()) {
         return "An empty table name cannot be captured.";
      }
      String name = tableName.toUpperCase(Locale.ROOT);
      if (name.startsWith(RECYCLE_BIN_PREFIX)) {
         return "Oracle recycle-bin table; not live application data.";
      }
      if (!name.matches("[A-Z0-9_$#]+")) {
         return "Table name is not a plain identifier supported by the capture engine.";
      }
      if (tableName.length() > MonitoringScope.CAPTURE_NAME_LIMIT) {
         return "Table name exceeds the " + MonitoringScope.CAPTURE_NAME_LIMIT
               + "-character persisted capture identifier limit.";
      }
      return null;
   }

   /** Non-overridable reasons, independent of operational opt-ins. */
   public String lockedReason(String tableName) {
      String unsupported = unsupportedNameReason(tableName);
      if (unsupported != null) return unsupported;
      String name = tableName.toUpperCase(Locale.ROOT);
      if (matchesAny(name, Arrays.asList(ALWAYS_EXCLUDED))) {
         return "Hard exclusion: DB Capture's own tables or Windchill upgrade scaffolding.";
      }
      for (String pattern : extraExclusions) {
         if (matches(name, pattern)) {
            return "Preserved extraExcludeTables exclusion: " + pattern + ".";
         }
      }
      for (String pattern : configuredExclusions) {
         if (matches(name, pattern)) {
            return "Site " + EXCLUDE_PROPERTY + " exclusion: " + pattern + ".";
         }
      }
      return null;
   }

   public String exclusionReason(String tableName) {
      String locked = lockedReason(tableName);
      if (locked != null) return locked;
      String name = tableName.toUpperCase(Locale.ROOT);
      return requestedIncludes.contains(name) ? null : MonitoringScope.defaultReason(name);
   }

   public Set<String> getRequestedIncludes() {
      return requestedIncludes;
   }

   private static boolean matches(String name, String pattern) {
      if ("*".equals(pattern)) return true;
      if (pattern.isEmpty()) return false;
      boolean prefixWildcard = pattern.startsWith("*");
      boolean suffixWildcard = pattern.endsWith("*");
      if (prefixWildcard && suffixWildcard) {
         return name.contains(pattern.substring(1, pattern.length() - 1));
      }
      if (suffixWildcard) {
         return name.startsWith(pattern.substring(0, pattern.length() - 1));
      }
      if (prefixWildcard) {
         return name.endsWith(pattern.substring(1));
      }
      return name.equals(pattern);
   }

   /** The effective exclusion patterns, for display on the settings page. */
   public List<String> getPatterns() {
      return patterns;
   }
}
