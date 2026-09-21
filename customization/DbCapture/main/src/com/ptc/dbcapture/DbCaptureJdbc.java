package com.ptc.dbcapture;

import java.sql.Connection;

import wt.method.MethodContext;
import wt.pom.WTConnection;
import wt.util.WTException;

/**
 * Hands out the JDBC connection of the current method context.
 *
 * Going through the method context means the pooled connection, the datastore
 * credentials and the transaction are all the ones Windchill is already using -
 * nothing here has to know or store a database password. This mirrors what
 * {@code com.ptc.windchill.upgrade.service.StandardPersistableModificationListernerService}
 * does internally.
 *
 * Every call must therefore happen server side, inside a method context.
 */
final class DbCaptureJdbc {

   private DbCaptureJdbc() {
   }

   static Connection connection() throws WTException {
      MethodContext context = MethodContext.getContext();
      if (context == null) {
         throw new WTException("DB Capture must run inside a method context "
               + "(server side); no active context was found.");
      }
      try {
         Object raw = context.getConnection();
         if (!(raw instanceof WTConnection)) {
            throw new WTException("Unexpected connection type from the method context: "
                  + (raw == null ? "null" : raw.getClass().getName()));
         }
         return ((WTConnection) raw).getConnection();
      } catch (WTException e) {
         throw e;
      } catch (Exception e) {
         throw new WTException(e);
      }
   }
}
