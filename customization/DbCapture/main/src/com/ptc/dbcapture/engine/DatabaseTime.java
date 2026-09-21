package com.ptc.dbcapture.engine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.TimeZone;

/** Oracle version timestamps are database-local wall times, not JVM-local times. */
public final class DatabaseTime {
   private DatabaseTime() {
   }

   public static TimeZone readZone(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement();
           ResultSet rows = statement.executeQuery(
                 "SELECT TO_CHAR(SYSTIMESTAMP, 'TZH:TZM') FROM DUAL")) {
         if (!rows.next()) {
            throw new SQLException("The database did not return its clock offset.");
         }
         try {
            return TimeZone.getTimeZone(ZoneOffset.of(rows.getString(1).trim()));
         } catch (java.time.DateTimeException | NullPointerException e) {
            throw new SQLException("The database returned an invalid clock offset.", e);
         }
      }
   }

   public static Timestamp withinWindow(Timestamp recorded, Timestamp start, Timestamp end,
                                         TimeZone databaseZone) {
      if (recorded == null || start == null || end == null) {
         return null;
      }
      if (inside(recorded.toInstant(), start, end)) {
         return recorded;
      }
      // Older captures stored a database wall time as if it were UTC.
      Instant corrected = recorded.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime()
            .atZone(databaseZone.toZoneId()).toInstant();
      return inside(corrected, start, end) ? Timestamp.from(corrected) : null;
   }

   private static boolean inside(Instant time, Timestamp start, Timestamp end) {
      // Oracle's SCN-to-time mapping and persisted DATE values have coarse precision.
      return !time.isBefore(start.toInstant().minusSeconds(10))
            && !time.isAfter(end.toInstant().plusSeconds(10));
   }
}
