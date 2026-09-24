package com.custom.dbcapture;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;

import com.custom.dbcapture.engine.TableFilter;
import com.custom.dbcapture.engine.MonitoringScope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import wt.util.WTProperties;

/**
 * Exact operational-table opt-ins, editable from the administration page.
 *
 * Held in a properties file under WT_HOME rather than in the datastore, so that
 * changing a setting does not mean another schema change - and so the values
 * survive the capture tables being dropped and recreated, which happens
 * whenever the model changes. A site with several method servers would need
 * this on shared storage; for a single-server troubleshooting tool a local file
 * is the right weight.
 *
 * The legacy group switches are retained for migration. Presence of the exact
 * includedTables key (including an empty value) supersedes those switches.
 */
public final class DbCaptureSettings {

   /** Legacy monitoring/logging switch, used only until an exact selection is saved. */
   public static final String INCLUDE_MONITORING = "includeMonitoringTables";
   /** Legacy queue switch, used only until an exact selection is saved. */
   public static final String INCLUDE_QUEUE = "includeQueueTables";
   /** Legacy statistics switch, used only until an exact selection is saved. */
   public static final String INCLUDE_STATISTICS = "includeStatisticsTables";
   /** Free text, comma separated, added on top of the built-in exclusions. */
   public static final String EXTRA_EXCLUDES = "extraExcludeTables";
   /** Complete desired selection of default-excluded tables to re-enable. */
   public static final String INCLUDED_TABLES = "includedTables";

   private static final String FILE_NAME = "settings.properties";
   private static final Logger LOG = LogManager.getLogger(DbCaptureSettings.class);

   private DbCaptureSettings() {
   }

   /** Where the file lives: WT_HOME/custom/DbCapture/settings.properties. */
   static File file() throws IOException {
      String home = WTProperties.getLocalProperties().getProperty("wt.home", ".");
      File dir = new File(new File(home, "custom"), "DbCapture");
      return new File(dir, FILE_NAME);
   }

   public static synchronized Properties load() {
      Properties props = new Properties();
      try {
         File f = file();
         if (Files.exists(f.toPath())) {
            try (java.io.InputStream in = Files.newInputStream(f.toPath())) {
               props.load(in);
            }
         }
      } catch (IOException e) {
         LOG.error("Could not read DB Capture monitoring settings.", e);
         throw new UncheckedIOException("Could not read DB Capture monitoring settings.", e);
      }
      return props;
   }

   public static synchronized void save(Properties props) throws IOException {
      Path target = file().toPath();
      Files.createDirectories(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), "settings-", ".tmp");
      try {
         try (java.io.OutputStream out = Files.newOutputStream(temporary)) {
            props.store(out, "DB Capture settings - edited from DB Ninja");
         }
         Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
         Files.deleteIfExists(temporary);
      }
   }

   public static boolean isEnabled(String key, boolean fallback) {
      return isEnabled(load(), key, fallback);
   }

   public static boolean isEnabled(Properties settings, String key, boolean fallback) {
      String raw = settings.getProperty(key);
      if (raw == null || raw.trim().isEmpty()) {
         return fallback;
      }
      if (!"true".equalsIgnoreCase(raw.trim()) && !"false".equalsIgnoreCase(raw.trim())) {
         throw new IllegalArgumentException("Invalid boolean setting: " + key);
      }
      return Boolean.parseBoolean(raw.trim());
   }

   public static String text(String key) {
      String raw = load().getProperty(key);
      return raw == null ? "" : raw;
   }

   /** Legacy/site setting edit; per-table opt-ins require updateIncludedTables. */
   public static synchronized void set(String key, String value) throws IOException {
      update(Map.of(key, value == null ? "" : value));
   }

   public static synchronized void update(Map<String, String> values) throws IOException {
      Properties props = load();
      for (Map.Entry<String, String> entry : values.entrySet()) {
         String key = entry.getKey();
         String value = entry.getValue() == null ? "" : entry.getValue().trim();
         if (EXTRA_EXCLUDES.equals(key)) {
            value = String.join(",", TableFilter.splitPatterns(value));
         } else if (INCLUDE_MONITORING.equals(key) || INCLUDE_QUEUE.equals(key)
               || INCLUDE_STATISTICS.equals(key)) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
               throw new IllegalArgumentException("Expected true or false for " + key);
            }
            value = value.toLowerCase(java.util.Locale.ROOT);
         } else {
            throw new IllegalArgumentException("Unknown monitoring setting: " + key);
         }
         props.setProperty(key, value);
      }
      save(props);
   }

   /**
    * An all-or-nothing edit validated against the server's physical catalog.
    * Extra/site exclusions and unrelated/legacy keys are never discarded.
    */
   public static synchronized MonitoringScope.Selection updateIncludedTables(String csv,
         MonitoringScope.Catalog catalog, String configuredExclusions) throws IOException {
      Properties props = load();
      java.util.List<String> included =
            MonitoringScope.validateIncludes(csv, props, configuredExclusions, catalog);
      props.setProperty(INCLUDED_TABLES, String.join(",", included));
      MonitoringScope.Selection result = MonitoringScope.describe(props, configuredExclusions, catalog);
      save(props);
      return result;
   }
}
