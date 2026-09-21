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
 * Everything one capture did to one datastore table, as a single row.
 *
 * A capture touches a handful of tables but often many rows of each, so listing
 * every changed column separately buries the shape of what happened. This is
 * the reading view: one line per table, with the per row and per column detail
 * carried in {@link #getDetails()} as text the page shows in a read-only box.
 * The individual {@link DbCaptureAttrDelta} rows remain for searching.
 */
@GenAsPersistable(
   properties = {
      @GeneratedProperty(name = "captureId", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 40)),
      @GeneratedProperty(name = "tableName", type = String.class,
         constraints = @PropertyConstraints(required = true, upperLimit = 40)),
      @GeneratedProperty(name = "className", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 200),
         javaDoc = "Windchill class the table maps to, e.g. wt.part.WTPart."),
      @GeneratedProperty(name = "operations", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 200),
         javaDoc = "What happened: CREATE, UPDATE, DELETE or LOGICAL_DELETE. "
                 + "One value, because a rollup row is one table and one "
                 + "operation. Plural for historical reasons - renaming it "
                 + "would mean a schema reinstall for a label."),
      @GeneratedProperty(name = "rowsAffected", type = int.class),
      @GeneratedProperty(name = "createdCount", type = int.class),
      @GeneratedProperty(name = "updatedCount", type = int.class),
      @GeneratedProperty(name = "deletedCount", type = int.class),
      @GeneratedProperty(name = "logicalDeletedCount", type = int.class),
      @GeneratedProperty(name = "objectIdentities", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000),
         javaDoc = "Business identifiers of the affected rows, when resolvable."),
      @GeneratedProperty(name = "actionNames", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000),
         javaDoc = "Distinct Windchill APIs seen for this table in the window."),
      @GeneratedProperty(name = "changedBy", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 400)),
      @GeneratedProperty(name = "firstChangeTime", type = java.sql.Timestamp.class),
      @GeneratedProperty(name = "lastChangeTime", type = java.sql.Timestamp.class),
      @GeneratedProperty(name = "details", type = String.class,
         columnProperties = @ColumnProperties(columnType = ColumnType.CLOB),
         javaDoc = "One line per changed column: row id, column, old -> new."),
      @GeneratedProperty(name = "note", type = String.class,
         constraints = @PropertyConstraints(upperLimit = 4000))
   },
   foreignKeys = {
      @GeneratedForeignKey(name = "DbCaptureSessionTableLink",
         foreignKeyRole = @ForeignKeyRole(name = "session", type = DbCaptureSession.class,
            constraints = @PropertyConstraints(required = true)),
         myRole = @MyRole(name = "tableChange"))
   },
   tableProperties = @TableProperties(
      compositeIndex1 = "sessionReference.key.id",
      compositeIndex2 = "captureId",
      compositeIndex3 = "+ captureId + tableName")
)
public class DbCaptureTableChange extends _DbCaptureTableChange {

   static final long serialVersionUID = 1L;

   public static DbCaptureTableChange newDbCaptureTableChange(DbCaptureSession session)
         throws WTException, WTPropertyVetoException {
      DbCaptureTableChange instance = new DbCaptureTableChange();
      instance.initialize();
      instance.setSession(session);
      return instance;
   }

   protected void initialize() throws WTException {
   }

   public String getIdentity() {
      return getTableName();
   }

   public void checkAttributes() throws InvalidAttributeException {
   }

   /** Everything a keyword search should look at, lower-cased. */
   public String searchableText() {
      StringBuilder sb = new StringBuilder(512);
      append(sb, getCaptureId());
      append(sb, getTableName());
      append(sb, getClassName());
      append(sb, getOperations());
      append(sb, getObjectIdentities());
      append(sb, getActionNames());
      append(sb, getChangedBy());
      append(sb, getDetails());
      append(sb, getNote());
      return sb.toString().toLowerCase(java.util.Locale.ROOT);
   }

   private static void append(StringBuilder sb, String value) {
      if (value != null) {
         sb.append(value).append('\n');
      }
   }
}
