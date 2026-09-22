package com.custom.dbcapture.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/**
 * Says which Windchill API and which page request produced a change.
 *
 * Flashback knows what the datastore did but nothing about why. Windchill
 * already writes the missing half into its own schema: turning on the
 * {@code wt.pom.sql} and {@code wt.pds.bind} loggers routes every statement and
 * its bind values into MiscLogEvents, and the MDC columns on that table join
 * straight to MethodContexts (user, TargetClass, TargetMethod) and
 * ServletRequests (RequestURI). Matching on the table name plus the row id
 * inside the logged statement is what fills the Action name and Stack trace
 * columns the specification asked for.
 *
 * Correlation is advisory. A miss leaves those columns empty; it never
 * invalidates the Flashback findings, which are the authoritative part.
 */
public final class LogCorrelator {

   /**
    * Widen the log window slightly past the capture window. The log appender is
    * asynchronous and MiscLogEvents timestamps can trail the commit that
    * Flashback timestamps.
    */
   private static final long WINDOW_SLACK_MS = 30_000L;

   private static final String SQL =
        "SELECT m.LE_MESSAGE, m.LE_MDC_USER, m.LE_THROWABLECLASS, "
      + "       c.TARGETCLASS, c.TARGETMETHOD, c.USERNAME, r.REQUESTURI "
      + "  FROM DBCAPTURESQLEVENTS m "
      + "  LEFT JOIN METHODCONTEXTS c ON c.ID = m.LE_MDC_METHODCONTEXTID "
      + "  LEFT JOIN SERVLETREQUESTS r ON r.ID = m.LE_MDC_SERVLETREQUESTID "
      + " WHERE m.LE_TIMESTAMP BETWEEN ? AND ? "
      + "   AND m.LE_LOGGERNAME IN ('wt.pom.sql', 'wt.pds.bind') "
      + "   AND UPPER(m.LE_MESSAGE) LIKE ? ";

   private final Connection connection;

   public LogCorrelator(Connection connection) {
      this.connection = connection;
   }

   /**
    * Builds a row id to cause map for one table over the capture window.
    *
    * Everything is read in one pass per table rather than per changed row: a
    * capture can hold thousands of rows and a query each would be far more
    * expensive than scanning the window's log entries once.
    */
   public Map<Long, CorrelationHit> correlate(String tableName, Timestamp from, Timestamp to) {
      Map<Long, CorrelationHit> byRowId = new HashMap<Long, CorrelationHit>();
      if (from == null || to == null) {
         return byRowId;
      }

      Timestamp lower = new Timestamp(from.getTime() - WINDOW_SLACK_MS);
      Timestamp upper = new Timestamp(to.getTime() + WINDOW_SLACK_MS);

      try (PreparedStatement ps = connection.prepareStatement(SQL)) {
         ps.setTimestamp(1, lower);
         ps.setTimestamp(2, upper);
         ps.setString(3, "%" + tableName.toUpperCase() + "%");
         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               String message = rs.getString(1);
               if (message == null) {
                  continue;
               }
               CorrelationHit hit = new CorrelationHit(
                     actionName(rs.getString(4), rs.getString(5)),
                     rs.getString(7),
                     firstNonEmpty(rs.getString(6), rs.getString(2)),
                     rs.getString(3));
               for (Long rowId : rowIdsIn(message)) {
                  // First writer wins: the earliest statement mentioning the
                  // row is the one that caused it.
                  if (!byRowId.containsKey(rowId)) {
                     byRowId.put(rowId, hit);
                  }
               }
            }
         }
      } catch (SQLException e) {
         // The loggers may be off, or the tables may not be readable. Either
         // way the capture proceeds without the enrichment.
         return byRowId;
      }
      return byRowId;
   }

   /**
    * Pulls candidate IDA2A2 values out of a logged statement.
    *
    * The statement text plus its bind values is free form, so this looks for
    * long integer literals - Windchill row ids are always numeric and long
    * enough not to collide with the small constants that appear in SQL.
    */
   static java.util.List<Long> rowIdsIn(String message) {
      java.util.List<Long> ids = new java.util.ArrayList<Long>();
      int i = 0;
      int n = message.length();
      while (i < n) {
         if (!Character.isDigit(message.charAt(i))) {
            i++;
            continue;
         }
         int start = i;
         while (i < n && Character.isDigit(message.charAt(i))) {
            i++;
         }
         // Windchill ida2a2 values are allocated from a sequence and are well
         // above four digits; shorter numbers are column sizes and the like.
         if (i - start >= 5 && i - start <= 19) {
            try {
               ids.add(Long.valueOf(message.substring(start, i)));
            } catch (NumberFormatException ignored) {
               // Out of long range; not a row id.
            }
         }
      }
      return ids;
   }

   private static String actionName(String targetClass, String targetMethod) {
      if (targetClass == null && targetMethod == null) {
         return null;
      }
      if (targetMethod == null) {
         return targetClass;
      }
      return (targetClass == null ? "?" : targetClass) + "." + targetMethod;
   }

   private static String firstNonEmpty(String a, String b) {
      if (a != null && !a.trim().isEmpty()) {
         return a;
      }
      return b != null && !b.trim().isEmpty() ? b : null;
   }
}
