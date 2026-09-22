package com.custom.dbcapture.mvc.builders;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.custom.dbcapture.DbCaptureAuthorization;
import com.custom.dbcapture.DbCaptureHelper;
import com.custom.dbcapture.DbCapturePresentation;
import com.custom.dbcapture.DbCaptureSearch;
import com.custom.dbcapture.DbCaptureSession;
import com.custom.dbcapture.DbCaptureTableChange;
import com.ptc.mvc.components.AbstractComponentBuilder;
import com.ptc.mvc.components.ComponentBuilder;
import com.ptc.mvc.components.ComponentConfig;
import com.ptc.mvc.components.ComponentConfigFactory;
import com.ptc.mvc.components.ComponentParams;
import com.ptc.mvc.components.TableConfig;

import wt.fc.QueryResult;
import wt.util.WTException;

/**
 * The capture sessions, shown above the results on the same page.
 *
 * This is the management list - what was captured, when, by whom, and how much
 * it found. The changes themselves are in the table below, so nothing here
 * navigates anywhere.
 */
@ComponentBuilder("dbcapture.sessionTable")
public class DbCaptureSessionTableBuilder extends AbstractComponentBuilder {

   public ComponentConfig buildComponentConfig(ComponentParams params) throws WTException {
      ComponentConfigFactory factory = getComponentConfigFactory();
      TableConfig table = factory.newTableConfig();
      table.setLabel("Capture Sessions");
      table.setSelectable(true);
      table.setConfigurable(true);
      table.setShowCount(true);
      table.setFindInTableEnabled(true);
      table.setActionModel("dbcapture session table toolbar");

      DbCaptureChangeTableBuilder.col(factory, table, "captureId", "Capture ID", true);
      DbCaptureChangeTableBuilder.col(factory, table, "description", "Description", true);
      diagnosticColumn(factory, table, "dbcCaptureSql", "SQL / Stack Trace", 145);
      DbCaptureChangeTableBuilder.col(factory, table, "startedBy", "Started By", true);
      DbCaptureChangeTableBuilder.col(factory, table, "startTime", "Start Time", true);
      DbCaptureChangeTableBuilder.col(factory, table, "endTime", "End Time", true);
      DbCaptureChangeTableBuilder.col(factory, table, "tablesChanged", "Tables", true);
      DbCaptureChangeTableBuilder.col(factory, table, "createdCount", "Created", true);
      DbCaptureChangeTableBuilder.col(factory, table, "updatedCount", "Updated", true);
      DbCaptureChangeTableBuilder.col(factory, table, "deletedCount", "Deleted", true);
      DbCaptureChangeTableBuilder.col(factory, table,
            "logicalDeletedCount", "Logically Deleted", true);

      // The SCN columns are deliberately not offered at all. They are Oracle's
      // internal transaction clock - meaningful to whoever re-runs a window by
      // hand in SQL, meaningless to the person reading the results, and the
      // values stay in the datastore for that rare case.
      diagnosticColumn(factory, table, "status", "Status", 70);
      DbCaptureChangeTableBuilder.col(factory, table, "captureMode", "Mode", true);
      diagnosticColumn(factory, table, "warnings", "Warnings", 500);
      return table;
   }

   private static void diagnosticColumn(ComponentConfigFactory factory, TableConfig table,
                                         String id, String label, int width) {
      com.ptc.mvc.components.ColumnConfig column = factory.newColumnConfig(id, label, false);
      column.setDataUtilityId("dbCaptureSessionDiagnostics");
      column.setWidth(width);
      if ("warnings".equals(id)) column.setVariableHeight(true);
      table.addComponent(column);
   }

   public Object buildComponentData(ComponentConfig config, ComponentParams params)
         throws Exception {

      DbCaptureAuthorization.requireAdministrator();

      // Opening the page must not read the whole history. The tables stay
      // empty until the user actually asks for something; the page sets
      // dbcSearched=true when Search or Clear is pressed.
      if (!DbCaptureChangeTableBuilder.isSearchRequested(params)) {
         return new ArrayList<DbCaptureSession>();
      }

      DbCaptureSearch keyword = DbCaptureSearch.forKeyword(
            DbCaptureChangeTableBuilder.stringParam(
                  params, DbCaptureChangeTableBuilder.PARAM_KEYWORD));
      boolean showUnfinished = DbCaptureChangeTableBuilder.showUnfinished(params);

      /*
       * A keyword has to reach the captures through their contents.
       *
       * A session only knows its own id, description, user and status, so
       * searching for a table or a column name matched nothing here and the
       * list came back empty while Database Changes filled up - which also
       * left the toolbar with no row to act on. A session now qualifies if it
       * matches on its own fields or if any of its change rows match.
       * Currently hidden affects Database Changes only, not this session list.
       */
      Set<String> matched = keyword == null
            ? null
            : capturesMatching(keyword);

      List<DbCaptureSession> rows = new ArrayList<DbCaptureSession>();
      QueryResult qr = DbCaptureHelper.findSessions();
      while (qr.hasMoreElements()) {
         DbCaptureSession session = (DbCaptureSession) qr.nextElement();
         if (!DbCaptureHelper.isVisible(session, showUnfinished)) {
            continue;
         }
         if (keyword == null
               || keyword.matches(session.searchableText())
               || matched.contains(session.getCaptureId())) {
            rows.add(session);
         }
      }
      return rows;
   }

   /** Capture ids with at least one change row matching the keyword. */
   private static Set<String> capturesMatching(DbCaptureSearch keyword)
         throws WTException {
      Set<String> ids = new LinkedHashSet<String>();
      List<DbCaptureTableChange> candidates = new ArrayList<>();
      QueryResult qr = DbCaptureHelper.findTableChanges();
      while (qr.hasMoreElements()) {
         DbCaptureTableChange row = (DbCaptureTableChange) qr.nextElement();
         if (row.getCaptureId() == null || ids.contains(row.getCaptureId())) {
            continue;
         }
         candidates.add(row);
      }
      DbCapturePresentation presentation = DbCapturePresentation.load(candidates);
      for (DbCaptureTableChange row : candidates) {
         if (presentation.matches(row, keyword)) {
            ids.add(row.getCaptureId());
         }
      }
      return ids;
   }
}
