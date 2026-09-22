package com.custom.dbcapture;

import com.ptc.windchill.annotations.metadata.GenAsPersistable;
import com.ptc.windchill.annotations.metadata.GeneratedProperty;
import com.ptc.windchill.annotations.metadata.PropertyConstraints;
import com.ptc.windchill.annotations.metadata.TableProperties;

import wt.fc.InvalidAttributeException;
import wt.util.WTException;

/**
 * One DB capture session: the window between the header Start and Stop buttons.
 *
 * At most one row is expected to carry {@link #STATUS_RUNNING} at any time -
 * monitoring is server-wide, so the active session is held in the datastore
 * rather than in a static field. That keeps the "one session" rule intact
 * across method server restarts and across cluster nodes.
 */
@GenAsPersistable(
   properties = {
      @GeneratedProperty(name = "captureId", type = String.class,
         constraints = @PropertyConstraints(required = true, upperLimit = 40),
         javaDoc = "Human readable session key, e.g. CAP-000123."),
      @GeneratedProperty(name = "description", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 400),
         javaDoc = "Free text entered when the capture starts; editable "
                 + "afterwards. Not named label: LABEL is an Oracle reserved word."),
      @GeneratedProperty(name = "status", type = String.class,
         constraints = @PropertyConstraints(required = true, upperLimit = 20),
         javaDoc = "RUNNING, COMPLETED, COMPLETED_WARNINGS, FAILED or ABORTED."),
      @GeneratedProperty(name = "captureMode", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 20),
         javaDoc = "Collection strategy: FLASHBACK, FLASHBACK+SNAPSHOT, ROWSCN "
                 + "or SNAPSHOT. Not named mode: MODE is an Oracle reserved word."),
      @GeneratedProperty(name = "startedBy", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 200),
         javaDoc = "Windchill user who pressed Start."),
      @GeneratedProperty(name = "startTime", type = java.sql.Timestamp.class),
      @GeneratedProperty(name = "endTime", type = java.sql.Timestamp.class),
      @GeneratedProperty(name = "startScn", type = long.class,
         javaDoc = "Oracle SCN captured at Start; lets the window be re-queried later."),
      @GeneratedProperty(name = "endScn", type = long.class),
      @GeneratedProperty(name = "tablesChanged", type = int.class),
      @GeneratedProperty(name = "createdCount", type = int.class),
      @GeneratedProperty(name = "updatedCount", type = int.class),
      @GeneratedProperty(name = "deletedCount", type = int.class),
      @GeneratedProperty(name = "logicalDeletedCount", type = int.class),
      @GeneratedProperty(name = "warnings", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000),
         javaDoc = "Tables that had to be skipped, truncated values, degraded mode, etc."),
      @GeneratedProperty(name = "errorText", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000))
   },
   tableProperties = @TableProperties(
      compositeIndex1 = "captureId",
      compositeIndex2 = "status",
      compositeIndex3 = "+ startTime")
)
public class DbCaptureSession extends _DbCaptureSession {

   static final long serialVersionUID = 1L;

   public static final String STATUS_RUNNING   = "RUNNING";
   public static final String STATUS_COMPLETED = "COMPLETED";
   public static final String STATUS_COMPLETED_WARNINGS = "COMPLETED_WARNINGS";
   public static final String STATUS_FAILED    = "FAILED";
   public static final String STATUS_ABORTED   = "ABORTED";

   public static final String MODE_FLASHBACK = "FLASHBACK";
   public static final String MODE_ROWSCN    = "ROWSCN";
   public static final String MODE_SNAPSHOT  = "SNAPSHOT";
   public static final String MODE_MIXED     = "FLASHBACK+SNAPSHOT";

   public static DbCaptureSession newDbCaptureSession() throws WTException {
      DbCaptureSession instance = new DbCaptureSession();
      instance.initialize();
      return instance;
   }

   protected void initialize() throws WTException {
      this.status = STATUS_RUNNING;
   }

   public String getIdentity() {
      return getCaptureId();
   }

   public void checkAttributes() throws InvalidAttributeException {
   }

   /** Elapsed seconds, or -1 while the session is still running. */
   public long getDurationSeconds() {
      if (getStartTime() == null || getEndTime() == null) {
         return -1L;
      }
      return (getEndTime().getTime() - getStartTime().getTime()) / 1000L;
   }

   public boolean isRunning() {
      return STATUS_RUNNING.equals(getStatus());
   }

   /** Everything a keyword search should look at, lower-cased. */
   public String searchableText() {
      StringBuilder sb = new StringBuilder(128);
      if (getCaptureId() != null) {
         sb.append(getCaptureId()).append('\n');
      }
      if (getDescription() != null) {
         sb.append(getDescription()).append('\n');
      }
      if (getStartedBy() != null) {
         sb.append(getStartedBy()).append('\n');
      }
      if (getStatus() != null) {
         sb.append(getStatus()).append('\n');
      }
      return sb.toString().toLowerCase(java.util.Locale.ROOT);
   }
}
