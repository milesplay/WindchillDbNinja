package com.custom.dbcapture.engine;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Turns Windchill's SQL loggers on for the length of a capture and puts them
 * back afterwards.
 *
 * These loggers are what feed MiscLogEvents, which is where {@link LogCorrelator}
 * reads the causing API from. They are deliberately not left on: at DEBUG they
 * record every statement the server executes, which is far too much for normal
 * running. Scoping them to the capture window keeps the cost where the
 * investigator asked for it.
 *
 * Restoration is best effort and uses only levels remembered in this process.
 * Service startup does not change logger levels.
 */
public final class SqlLogWindow {

   /** Statement text, bind values, and affected row counts. */
   static final String[] LOGGERS = {
      "wt.pom.sql", "wt.pds.bind", "wt.pom.rowCount"
   };

   private final Map<String, Level> previousLevels = new LinkedHashMap<String, Level>();
   private boolean open;

   /** Raises the SQL loggers to DEBUG, remembering what they were. */
   public void open() {
      if (open) {
         return;
      }
      for (String name : LOGGERS) {
         try {
            previousLevels.put(name, LogManager.getLogger(name).getLevel());
            Configurator.setLevel(name, Level.DEBUG);
         } catch (Exception e) {
            // A logger that cannot be adjusted only costs enrichment.
            previousLevels.remove(name);
         }
      }
      open = true;
   }

   /** Puts every logger back to the level it had before {@link #open()}. */
   public void close() {
      for (Map.Entry<String, Level> entry : previousLevels.entrySet()) {
         try {
            Configurator.setLevel(entry.getKey(), entry.getValue());
         } catch (Exception ignored) {
            // Restoration failures require manual logger configuration.
         }
      }
      previousLevels.clear();
      open = false;
   }

   /** Forces the loggers back off, used to clean up after an abandoned session. */
   public static void resetAll() {
      for (String name : LOGGERS) {
         try {
            Configurator.setLevel(name, Level.ERROR);
         } catch (Exception ignored) {
            // Best effort.
         }
      }
   }

   public boolean isOpen() {
      return open;
   }
}
