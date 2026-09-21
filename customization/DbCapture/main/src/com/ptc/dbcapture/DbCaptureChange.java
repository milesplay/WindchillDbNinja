package com.ptc.dbcapture;

import com.ptc.windchill.annotations.metadata.ColumnProperties;
import com.ptc.windchill.annotations.metadata.ColumnType;
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
 * One changed datastore row inside a capture window.
 *
 * This is the granularity DBDiff2 reported (table + ida2a2 + kind). The column
 * level detail hangs off this as {@link DbCaptureAttrDelta}.
 */
@GenAsPersistable(
   properties = {
      @GeneratedProperty(name = "tableName", type = String.class,
         constraints = @PropertyConstraints(required = true, upperLimit = 40)),
      @GeneratedProperty(name = "className", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 200),
         javaDoc = "Windchill class the table maps to, resolved via wt.introspection."),
      @GeneratedProperty(name = "operation", type = String.class,
         constraints = @PropertyConstraints(required = true, upperLimit = 20),
         javaDoc = "CREATE, UPDATE, DELETE or LOGICAL_DELETE."),
      @GeneratedProperty(name = "targetRowId", type = long.class,
         javaDoc = "IDA2A2 of the affected row. Not named rowId: ROWID is an "
                 + "Oracle reserved word and the SQL generator rejects it."),
      @GeneratedProperty(name = "objectIdentity", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 400),
         javaDoc = "Business identifier (number/name/version) when it could be resolved."),
      @GeneratedProperty(name = "changedColumns", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000),
         javaDoc = "Comma separated column names, so the summary row stays searchable."),
      @GeneratedProperty(name = "changeTime", type = java.sql.Timestamp.class,
         javaDoc = "VERSIONS_STARTTIME. Windchill maps Timestamp to an Oracle DATE, "
                 + "so this is only second accurate; use changeScn to order exactly."),
      @GeneratedProperty(name = "changeScn", type = long.class,
         javaDoc = "VERSIONS_STARTSCN. The exact, monotonic order in which the "
                 + "datastore applied the changes, which the DATE column cannot express."),
      @GeneratedProperty(name = "transactionId", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 40),
         javaDoc = "VERSIONS_XID; groups every row touched by one transaction."),
      @GeneratedProperty(name = "actionName", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 400),
         javaDoc = "TargetClass.TargetMethod from MethodContexts, when correlation succeeded."),
      @GeneratedProperty(name = "requestUri", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 400),
         javaDoc = "Originating servlet request from ServletRequests."),
      @GeneratedProperty(name = "changedBy", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 200)),
      @GeneratedProperty(name = "stackTrace", type = String.class,
         columnProperties = @ColumnProperties(columnType = ColumnType.CLOB),
         javaDoc = "Java stack recorded for the statement, when available."),
      @GeneratedProperty(name = "undoSql", type = String.class,
         columnProperties = @ColumnProperties(columnType = ColumnType.CLOB),
         javaDoc = "FLASHBACK_TRANSACTION_QUERY.UNDO_SQL; needs supplemental logging."),
      @GeneratedProperty(name = "note", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000),
         javaDoc = "Investigator's own annotation; editable from the results table.")
   },
   foreignKeys = {
      @GeneratedForeignKey(name = "DbCaptureSessionChangeLink",
         foreignKeyRole = @ForeignKeyRole(name = "session", type = DbCaptureSession.class,
            constraints = @PropertyConstraints(required = true)),
         myRole = @MyRole(name = "change"))
   },
   tableProperties = @TableProperties(
      compositeIndex1 = "sessionReference.key.id",
      compositeIndex2 = "tableName",
      compositeIndex3 = "+ sessionReference.key.id + tableName + targetRowId",
      compositeIndex4 = "transactionId")
)
public class DbCaptureChange extends _DbCaptureChange {

   static final long serialVersionUID = 1L;

   public static final String OP_CREATE         = "CREATE";
   public static final String OP_UPDATE         = "UPDATE";
   public static final String OP_DELETE         = "DELETE";
   /** Windchill flips markForDeleteA2 instead of issuing a DELETE for many removals. */
   public static final String OP_LOGICAL_DELETE = "LOGICAL_DELETE";

   public static DbCaptureChange newDbCaptureChange(DbCaptureSession session)
         throws WTException, WTPropertyVetoException {
      DbCaptureChange instance = new DbCaptureChange();
      instance.initialize();
      instance.setSession(session);
      return instance;
   }

   protected void initialize() throws WTException {
   }

   public String getIdentity() {
      return getTableName() + " : " + getTargetRowId();
   }

   public void checkAttributes() throws InvalidAttributeException {
   }
}
