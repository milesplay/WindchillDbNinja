package com.ptc.dbcapture.mvc.builders;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ptc.dbcapture.DbCaptureAuthorization;
import com.ptc.dbcapture.DbCaptureHelper;
import com.ptc.dbcapture.DbCapturePresentation;
import com.ptc.dbcapture.DbCaptureSearch;
import com.ptc.dbcapture.DbCaptureTableChange;
import com.ptc.dbcapture.engine.TableFilter;
import com.ptc.mvc.components.AbstractComponentBuilder;
import com.ptc.mvc.components.ComponentBuilder;
import com.ptc.mvc.components.ComponentConfig;
import com.ptc.mvc.components.ComponentConfigFactory;
import com.ptc.mvc.components.ComponentParams;
import com.ptc.mvc.components.TableConfig;

import wt.fc.QueryResult;
import wt.util.WTException;

/**
 * The results table: one row per table and operation, across every capture.
 *
 * This is the answer to the question the tool exists for - after that click in
 * the UI, which tables moved and what happened to them. One row says "WTPART,
 * UPDATE, 3 rows" and carries the column level old and new values with it, in
 * a block you can read straight down.
 *
 * It used to be one row per changed <i>column</i>, which meant a single edit to
 * one object spread over a dozen near-identical rows and the shape of the
 * change was lost in them. The per column detail is not gone - it moved into
 * the Changed Columns block of the row it belongs to - and the underlying
 * DbCaptureAttrDelta records are still captured and stored either way.
 */
@ComponentBuilder("dbcapture.changeTable")
public class DbCaptureChangeTableBuilder extends AbstractComponentBuilder {

   static final String PARAM_KEYWORD = "dbcKeyword";
   static final String PARAM_CAPTURE = "dbcCaptureId";
   /** Set by the page when Search or Clear is pressed; absent on first load. */
   static final String PARAM_SEARCHED = "dbcSearched";
   /** Comma separated TableFilter.DISPLAY_GROUPS keys to leave out. */
   static final String PARAM_HIDE_GROUPS = "dbcHideGroups";
   /** Comma separated exact table names to leave out. */
   static final String PARAM_HIDE_TABLES = "dbcHideTables";
   /** Show captures that are still running, or that failed or were aborted. */
   static final String PARAM_SHOW_UNFINISHED = "dbcShowUnfinished";

   public ComponentConfig buildComponentConfig(ComponentParams params) throws WTException {
      ComponentConfigFactory factory = getComponentConfigFactory();
      TableConfig table = factory.newTableConfig();
      table.setLabel("Database Changes");
      table.setSelectable(false);
      table.setConfigurable(true);
      table.setShowCount(true);
      table.setFindInTableEnabled(true);
      table.setActionModel("dbcapture change table toolbar");

      // Ordered by table, operation, object, then column detail.
      col(factory, table, "captureId", "Capture ID", true);
      col(factory, table, "tableName", "Table", true);
      col(factory, table, "className", "Class (API)", true);

      // Deliberately the plural property. One operation per row now, so the
      // label is singular, but the property keeps its generated name - a
      // rename would mean a model change and a schema reinstall for nothing.
      col(factory, table, "operations", "Operation", true);

      multilineCol(factory, table, "objectIdentities", "Object");
      col(factory, table, "dbcObjectCount", "Rows", true);
      multilineCol(factory, table, "dbcObjectName", "Name");
      multilineCol(factory, table, "dbcObjectNumber", "Number");
      multilineCol(factory, table, "details", "Changed Columns");
      multilineCol(factory, table, "lastChangeTime", "Changed At");

      // Available from the column chooser, off by default - useful when
      // digging, noise when scanning.
      col(factory, table, "firstChangeTime", "First Changed At", false);
      col(factory, table, "createdCount", "Created", false);
      col(factory, table, "updatedCount", "Updated", false);
      col(factory, table, "deletedCount", "Deleted", false);
      col(factory, table, "logicalDeletedCount", "Logically Deleted", false);
      col(factory, table, "note", "Note", false);

      // Not offered: actionNames and changedBy. Both come from the log
      // correlation, both have been empty on every capture so far, and a
      // column that is always blank teaches the reader to ignore that part of
      // the table. The values are still collected and stored, so putting the
      // columns back is one line once the correlation is fixed.
      return table;
   }

   public Object buildComponentData(ComponentConfig config, ComponentParams params)
         throws Exception {

      DbCaptureAuthorization.requireAdministrator();

      // A capture can hold a lot of rows, so the page opens empty and reads
      // nothing until a search is requested.
      if (!isSearchRequested(params)) {
         return new ArrayList<DbCaptureTableChange>();
      }

      DbCaptureSearch keyword =
            DbCaptureSearch.forKeyword(stringParam(params, PARAM_KEYWORD));
      String captureId = stringParam(params, PARAM_CAPTURE);
      Set<String> visible =
            DbCaptureHelper.visibleCaptureIds(showUnfinished(params));
      List<String> hidden = hiddenPatterns(params);

      List<DbCaptureTableChange> candidates = new ArrayList<DbCaptureTableChange>();
      QueryResult qr = DbCaptureHelper.findTableChanges();
      while (qr.hasMoreElements()) {
         DbCaptureTableChange row = (DbCaptureTableChange) qr.nextElement();
         if (!visible.contains(row.getCaptureId())) {
            continue;
         }
         if (captureId != null && !captureId.equals(row.getCaptureId())) {
            continue;
         }
         if (!hidden.isEmpty()
               && TableFilter.matchesAny(row.getTableName(), hidden)) {
            continue;
         }
         candidates.add(row);
      }
      DbCapturePresentation presentation = DbCapturePresentation.load(candidates);
      if (params instanceof com.ptc.jca.mvc.components.JcaComponentParams) {
         ((com.ptc.jca.mvc.components.JcaComponentParams) params).getNmCommandBean().getRequest()
               .setAttribute(DbCapturePresentation.REQUEST_KEY, presentation);
      }
      List<DbCaptureTableChange> rows = new ArrayList<>();
      for (DbCaptureTableChange row : candidates) {
         if (presentation.matches(row, keyword)) rows.add(row);
      }
      return rows;
   }

   /**
    * The table name patterns to leave out of the display.
    *
    * Filtering here rather than in the browser keeps the noise from having to
    * come down the wire before it is dropped. It is display-only: nothing is
    * deleted, so removing a Currently hidden entry brings the rows back.
    *
    * Note the two different splitters. Table names are upper cased, because
    * that is what the datastore holds; group keys are not, because they are
    * camel case identifiers. Running the keys through splitPatterns is exactly
    * how group hiding came to do nothing at all - "TASKEVENTS" never equalled
    * "taskEvents", so patternsForGroups always returned an empty list.
    */
   static List<String> hiddenPatterns(ComponentParams params) {
      List<String> hidden = TableFilter.patternsForGroups(
            TableFilter.splitKeys(stringParam(params, PARAM_HIDE_GROUPS)));
      hidden.addAll(
            TableFilter.splitPatterns(stringParam(params, PARAM_HIDE_TABLES)));
      return hidden;
   }

   /** True once the page has asked for results. */
   static boolean isSearchRequested(ComponentParams params) {
      return stringParam(params, PARAM_SEARCHED) != null;
   }

   static boolean showUnfinished(ComponentParams params) {
      return "true".equalsIgnoreCase(stringParam(params, PARAM_SHOW_UNFINISHED));
   }

   static void col(ComponentConfigFactory factory, TableConfig table,
                   String id, String label, boolean visible) {
      com.ptc.mvc.components.ColumnConfig column =
            factory.newColumnConfig(id, label, true);
      configureReportColumn(column, id);
      if (!visible) {
         column.setHidden(true);
      }
      table.addComponent(column);
   }

   /**
    * A column whose value is a block of text rather than one value.
    *
    * The same treatment the Warnings column on the sessions table gets, which
    * is the rendering the user asked this to match: let the cell wrap and let
    * the row grow to fit it, instead of clipping a multi-line value to one
    * line.
    */
   static void multilineCol(ComponentConfigFactory factory, TableConfig table,
                            String id, String label) {
      com.ptc.mvc.components.ColumnConfig column =
            factory.newColumnConfig(id, label, true);
      configureReportColumn(column, id);
      column.setColumnWrapped(true);
      column.setVariableHeight(true);
      column.setWidth(420);
      table.addComponent(column);
   }

   private static void configureReportColumn(com.ptc.mvc.components.ColumnConfig column, String id) {
      if ("objectIdentities".equals(id) || "details".equals(id)
            || "dbcObjectName".equals(id) || "dbcObjectNumber".equals(id)
            || "dbcObjectCount".equals(id)
            || "lastChangeTime".equals(id) || "firstChangeTime".equals(id)) {
         column.setDataUtilityId("dbCaptureReport");
      }
   }

   static String stringParam(ComponentParams params, String name) {
      Object value = params.getParameter(name);
      if (value == null) {
         return null;
      }
      String s = String.valueOf(value).trim();
      return s.isEmpty() ? null : s;
   }

   static String lower(String s) {
      return s == null ? null : s.toLowerCase();
   }
}
