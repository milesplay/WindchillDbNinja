package com.custom.dbcapture.engine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Database locks that serialize Start/Stop across users and MethodServers. */
public final class CaptureConcurrency {

   private static final int ORA_RESOURCE_BUSY = 54;

   private CaptureConcurrency() {
   }

   public static void lockForStart(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement()) {
         statement.setQueryTimeout(10);
         statement.execute("LOCK TABLE DBCAPTURESESSION IN EXCLUSIVE MODE NOWAIT");
      } catch (SQLException failure) {
         if (failure.getErrorCode() == ORA_RESOURCE_BUSY) {
            throw new SQLException("Another DB Capture Start or Stop is already in progress. "
                  + "Reload the header state and try again after it completes.",
                  failure.getSQLState(), failure.getErrorCode(), failure);
         }
         throw failure;
      }
   }

   public static Long lockRunningSession(Connection connection) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(
               "SELECT IDA2A2 FROM DBCAPTURESESSION WHERE STATUS=? FOR UPDATE NOWAIT")) {
         statement.setQueryTimeout(10);
         statement.setString(1, "RUNNING");
         try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
               return null;
            }
            long id = rows.getLong(1);
            if (rows.next()) {
               throw new SQLException("More than one RUNNING DB Capture exists; "
                     + "Stop collection and repair the session state before continuing.");
            }
            return Long.valueOf(id);
         }
      } catch (SQLException failure) {
         if (failure.getErrorCode() == ORA_RESOURCE_BUSY) {
            throw new SQLException("DB Capture Stop is already running. Wait for it to complete.",
                  failure.getSQLState(), failure.getErrorCode(), failure);
         }
         throw failure;
      }
   }
}
