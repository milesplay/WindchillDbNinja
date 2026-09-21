package com.ptc.dbcapture.engine;

import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import wt.fc.IdentificationObject;
import wt.fc.Identified;
import wt.fc.ObjectIdentifier;
import wt.fc.Persistable;
import wt.fc.PersistenceHelper;
import wt.util.WTException;

/**
 * Turns a raw {@code table + IDA2A2} into something an investigator recognises.
 *
 * "WTPART : 866008" is what DBDiff2 could tell you; "0000000123, A.1" is what
 * you actually need to know which part was touched. Resolution is best effort
 * by design - a deleted row has nothing left to look up, and a capture must
 * never fail because a label could not be produced.
 */
public class IdentityResolver {
   private static final Logger LOG = LogManager.getLogger(IdentityResolver.class);

   public record Names(String name, String number) { }

   public Names namesFromLiveObject(String className, long rowId) {
      if (className == null || className.isBlank() || rowId <= 0) return new Names(null, null);
      try {
         Persistable object = PersistenceHelper.manager.refresh(
               ObjectIdentifier.newObjectIdentifier(className, rowId));
         return object == null ? new Names(null, null)
               : new Names(stringProperty(object, "getName"), stringProperty(object, "getNumber"));
      } catch (WTException | IllegalAccessException | InvocationTargetException e) {
         LOG.debug("Current name/number unavailable for {}:{}; captured values are retained.",
               className, rowId, e);
         return new Names(null, null);
      }
   }

   private static String stringProperty(Persistable object, String getter)
         throws IllegalAccessException, InvocationTargetException {
      Method method;
      try {
         method = object.getClass().getMethod(getter);
      } catch (NoSuchMethodException e) {
         return null; // Many infrastructure objects have neither business attribute.
      }
      return method.getReturnType() == String.class ? (String) method.invoke(object) : null;
   }

   /**
    * Columns worth falling back to, in preference order, when the live object
    * cannot be loaded. These cover the common Windchill naming attributes.
    */
   private static final String[] FALLBACK_COLUMNS = {
      "WTPARTNUMBER", "NUMBER", "NAME", "IDENTIFIER", "TITLE",
      "FILENAME", "INTERNALNAME", "SYMBOLICID"
   };

   public String resolve(String className, long rowId, Map<String, String> columns) {
      String live = fromLiveObject(className, rowId);
      if (live != null) {
         return live;
      }
      return fromColumns(columns);
   }

   private String fromLiveObject(String className, long rowId) {
      if (className == null || className.isEmpty() || rowId <= 0L) {
         return null;
      }
      try {
         ObjectIdentifier oid = ObjectIdentifier.newObjectIdentifier(className, rowId);
         Persistable p = PersistenceHelper.manager.refresh(oid);
         return p == null ? null : displayOf(p);
      } catch (Exception e) {
         // Deleted rows, types that are no longer installed and access
         // restrictions all land here. None of them is a capture failure.
         return null;
      }
   }

   private static String displayOf(Persistable p) {
      try {
         if (p instanceof Identified) {
            IdentificationObject io = ((Identified) p).getIdentificationObject();
            if (io != null) {
               return clip(io.getIdentity());
            }
         }
      } catch (Exception ignored) {
         // Not every persistable carries an identification object; fall back.
      }
      return clip(String.valueOf(p));
   }

   private static String clip(String s) {
      if (s == null) {
         return null;
      }
      return s.length() > 400 ? s.substring(0, 400) : s;
   }

   private static String fromColumns(Map<String, String> columns) {
      if (columns == null) {
         return null;
      }
      for (String candidate : FALLBACK_COLUMNS) {
         String value = columns.get(candidate);
         if (value != null && !value.trim().isEmpty()) {
            return candidate.toLowerCase() + "=" + value.trim();
         }
      }
      return null;
   }
}
