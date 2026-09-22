package com.custom.dbcapture;

import com.ptc.windchill.annotations.metadata.ForeignKeyRole;
import com.ptc.windchill.annotations.metadata.GenAsPersistable;
import com.ptc.windchill.annotations.metadata.GeneratedForeignKey;
import com.ptc.windchill.annotations.metadata.GeneratedProperty;
import com.ptc.windchill.annotations.metadata.MyRole;
import com.ptc.windchill.annotations.metadata.PropertyConstraints;
import com.ptc.windchill.annotations.metadata.TableProperties;

import wt.fc.InvalidAttributeException;
import wt.util.WTException;
import wt.util.WTPropertyVetoException;

/**
 * One changed column: which table, which row, which column, old value beside
 * new value.
 *
 * This is the row the investigator actually reads, so it carries its own
 * context rather than only a link to the parent {@link DbCaptureChange}. The
 * duplication is deliberate: a capture record is written once and never
 * updated, and denormalising lets the whole result be one flat, sortable,
 * searchable table instead of a master/detail pair the reader has to navigate
 * between. Correctness risk from the duplication is nil because nothing ever
 * edits these fields after the capture that produced them.
 */
@GenAsPersistable(
   properties = {
      /* --- what changed ------------------------------------------------ */
      @GeneratedProperty(name = "columnName", type = String.class,
         constraints = @PropertyConstraints(required = true, upperLimit = 40)),
      @GeneratedProperty(name = "attributeName", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 200),
         javaDoc = "Modeled attribute the column maps to, when resolvable."),
      @GeneratedProperty(name = "oldValue", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000)),
      @GeneratedProperty(name = "newValue", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000)),
      @GeneratedProperty(name = "truncated", type = boolean.class,
         javaDoc = "True when a value was longer than the 4000 character column."),

      /* --- context, copied from the parent change ----------------------- */
      @GeneratedProperty(name = "captureId", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 40)),
      @GeneratedProperty(name = "tableName", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 40)),
      @GeneratedProperty(name = "className", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 200),
         javaDoc = "Windchill class the table maps to, e.g. wt.part.WTPart."),
      @GeneratedProperty(name = "operation", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 20),
         javaDoc = "CREATE, UPDATE, DELETE or LOGICAL_DELETE."),
      @GeneratedProperty(name = "targetRowId", type = long.class,
         javaDoc = "IDA2A2 of the affected row."),
      @GeneratedProperty(name = "objectIdentity", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 400)),
      @GeneratedProperty(name = "changeTime", type = java.sql.Timestamp.class),
      @GeneratedProperty(name = "changeScn", type = long.class),
      @GeneratedProperty(name = "transactionId", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 40)),
      @GeneratedProperty(name = "actionName", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 400),
         javaDoc = "Windchill API that caused it, from MethodContexts."),
      @GeneratedProperty(name = "changedBy", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 200)),
      @GeneratedProperty(name = "requestUri", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 400))
   },
   foreignKeys = {
      @GeneratedForeignKey(name = "DbCaptureChangeDeltaLink",
         foreignKeyRole = @ForeignKeyRole(name = "change", type = DbCaptureChange.class,
            constraints = @PropertyConstraints(required = true)),
         myRole = @MyRole(name = "delta"))
   },
   tableProperties = @TableProperties(
      compositeIndex1 = "changeReference.key.id",
      compositeIndex2 = "captureId",
      compositeIndex3 = "+ captureId + tableName",
      compositeIndex4 = "columnName")
)
public class DbCaptureAttrDelta extends _DbCaptureAttrDelta {

   static final long serialVersionUID = 1L;

   /** Widest value the datastore column accepts; longer values are cut and flagged. */
   public static final int VALUE_LIMIT = 4000;

   public static DbCaptureAttrDelta newDbCaptureAttrDelta(DbCaptureChange change)
         throws WTException, WTPropertyVetoException {
      DbCaptureAttrDelta instance = new DbCaptureAttrDelta();
      instance.initialize();
      instance.setChange(change);
      return instance;
   }

   protected void initialize() throws WTException {
   }

   public String getIdentity() {
      return getTableName() + "." + getColumnName() + " [" + getTargetRowId() + "]";
   }

   public void checkAttributes() throws InvalidAttributeException {
   }

   /** Everything a keyword search should look at, lower-cased. */
   public String searchableText() {
      StringBuilder sb = new StringBuilder(256);
      append(sb, getCaptureId());
      append(sb, getTableName());
      append(sb, getClassName());
      append(sb, getOperation());
      append(sb, getColumnName());
      append(sb, getAttributeName());
      append(sb, getObjectIdentity());
      append(sb, getActionName());
      append(sb, getChangedBy());
      append(sb, getOldValue());
      append(sb, getNewValue());
      append(sb, String.valueOf(getTargetRowId()));
      return sb.toString().toLowerCase(java.util.Locale.ROOT);
   }

   private static void append(StringBuilder sb, String value) {
      if (value != null) {
         sb.append(value).append('\n');
      }
   }
}
