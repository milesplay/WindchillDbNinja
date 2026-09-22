package com.custom.dbcapture;

import com.custom.dbcapture.engine.MonitoringScope;
import com.custom.dbcapture.engine.TableFilter;
import java.lang.reflect.InvocationTargetException;
import wt.method.RemoteAccess;
import wt.method.RemoteMethodServer;
import wt.util.WTException;

/** On-demand administrator scope operations, executed in a method context. */
public final class DbCaptureMonitoringScope implements RemoteAccess {
   private DbCaptureMonitoringScope() {
   }

   public static MonitoringScope.Selection read() throws WTException {
      if (!RemoteMethodServer.ServerFlag) {
         return invoke("read", new Class<?>[0], new Object[0]);
      }
      DbCaptureAuthorization.requireAdministrator();
      try {
         return MonitoringScope.describe(DbCaptureSettings.load(), TableFilter.configuredExclusions(),
               MonitoringScope.readCatalog(DbCaptureJdbc.connection()));
      } catch (java.sql.SQLException e) {
         throw new WTException(e, "Could not read the physical monitoring-scope catalog.");
      }
   }

   public static MonitoringScope.Selection update(String includedTables) throws WTException {
      if (!RemoteMethodServer.ServerFlag) {
         return invoke("update", new Class<?>[] { String.class }, new Object[] { includedTables });
      }
      DbCaptureAuthorization.requireAdministrator();
      try {
         return DbCaptureSettings.updateIncludedTables(includedTables,
               MonitoringScope.readCatalog(DbCaptureJdbc.connection()), TableFilter.configuredExclusions());
      } catch (java.sql.SQLException | java.io.IOException e) {
         throw new WTException(e, "Could not save the monitoring scope. " + e.getMessage());
      }
   }

   private static MonitoringScope.Selection invoke(String method, Class<?>[] types, Object[] args)
         throws WTException {
      try {
         return (MonitoringScope.Selection) RemoteMethodServer.getDefault().invoke(
               method, DbCaptureMonitoringScope.class.getName(), null, types, args);
      } catch (InvocationTargetException e) {
         Throwable cause = e.getTargetException();
         if (cause instanceof WTException) throw (WTException) cause;
         if (cause instanceof RuntimeException) throw (RuntimeException) cause;
         throw new WTException(cause);
      } catch (java.rmi.RemoteException e) {
         throw new WTException(e, "Could not contact the method server for monitoring scope.");
      }
   }
}
