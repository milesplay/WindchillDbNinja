<%--
  DB Ninja administration - everything on one page.

  Layout follows Workflow Process Administration: a criteria area on top, then
  the tables, with no drill-down. The upper table is the list of captures; the
  lower one is the actual answer - every changed column of every changed row,
  flat, so it can be sorted and scanned without navigating anywhere.

  One keyword box searches both tables. It matches capture id, description,
  table name, class/API name, operation, column name, object identity, action
  name, user, and the old and new values.
--%><%@ taglib uri="http://www.ptc.com/windchill/taglib/mvc" prefix="mvc"%>
<%@ taglib uri="http://www.ptc.com/windchill/taglib/components" prefix="jca"%>
<%@ taglib uri="http://www.ptc.com/windchill/taglib/wrappers" prefix="w"%>
<%@ taglib uri="http://www.ptc.com/windchill/taglib/search" prefix="s"%>

<%@ include file="/netmarkets/jsp/util/begin.jspf"%>

<%
   String dbcCompletedCapture = request.getParameter("dbcCompletedCapture");
   String dbcCompletionMessage = null;
   String dbcCompletionProblem = null;
   if (dbcCompletedCapture == null) {
      dbcCompletedCapture = "";
   } else {
      try {
         com.ptc.dbcapture.DbCaptureAuthorization.requireAdministrator();
         if (!dbcCompletedCapture.matches("CAP-[0-9]{1,36}")) {
            throw new wt.util.WTException("Invalid completed capture ID.");
         }
         com.ptc.dbcapture.DbCaptureSession completed =
               com.ptc.dbcapture.DbCaptureHelper.findSession(dbcCompletedCapture);
         if (!com.ptc.dbcapture.DbCaptureHelper.isVisible(completed, false)) {
            throw new wt.util.WTException("The completed capture could not be found: "
                  + dbcCompletedCapture + ". Use Search to choose another capture.");
         }
         dbcCompletionMessage = com.ptc.dbcapture.DbCaptureHelper.completionMessage(completed);
      } catch (wt.util.WTException e) {
         dbcCompletedCapture = "";
         dbcCompletionProblem = e.getLocalizedMessage();
         org.apache.logging.log4j.LogManager.getLogger("com.ptc.dbcapture.results")
               .error("Could not open completed capture results.", e);
      }
   }
%>
<% if (dbcCompletionMessage != null) { %>
   <div id="dbcCompletionStatus" role="status" style="padding: 8px 0;">
      <%=wt.util.HTMLEncoder.encodeForHTMLContent(dbcCompletionMessage)%>
   </div>
<% } else if (dbcCompletionProblem != null) { %>
   <div role="alert" style="padding: 8px 0; color: #a00;">
      <%=wt.util.HTMLEncoder.encodeForHTMLContent(dbcCompletionProblem)%>
   </div>
<% } %>

<s:searchFieldSet legend="Monitoring scope" id="dbCaptureScopePanel" collapsed="true">
   <div class="dbcScopeEditor">
      <p>Business tables are captured by default. The lists below contain operational tables
         normally excluded from capture. Click <b>Edit</b>, select tables and use the arrows
         to include or exclude them, then <b>Save</b>. Changes apply to the <b>next capture</b>,
         never to a capture already running or to saved results.</p>
      <div class="dbcScopeTools">
         <label for="dbcScopeFind">Filter table lists</label>
         <input type="text" id="dbcScopeFind" disabled="disabled" autocomplete="off"/>
         <input type="button" id="dbcEditScopeButton" value="Edit" disabled="disabled"/>
         <input type="button" id="dbcSaveScopeButton" value="Save" disabled="disabled"/>
         <input type="button" id="dbcCancelScopeButton" value="Cancel" disabled="disabled"/>
         <input type="button" id="dbcReloadScopeButton" value="Reload" disabled="disabled"/>
      </div>
      <div class="dbcScopeLists">
         <div class="dbcScopeList">
            <label for="dbcScopeExcluded">Not captured by default
               <span id="dbcScopeExcludedCount"></span></label>
            <select id="dbcScopeExcluded" multiple="multiple" size="12" disabled="disabled"
                    aria-describedby="dbcScopeStatus"></select>
         </div>
         <div class="dbcScopeArrows">
            <button type="button" id="dbcIncludeScopeButton" disabled="disabled"
                    title="Include selected tables in future captures"
                    aria-label="Include selected tables">&#9654;</button>
            <button type="button" id="dbcExcludeScopeButton" disabled="disabled"
                    title="Exclude selected tables from future captures"
                    aria-label="Exclude selected tables">&#9664;</button>
         </div>
         <div class="dbcScopeList">
            <label for="dbcScopeIncluded">Include in future captures
               <span id="dbcScopeIncludedCount"></span></label>
            <select id="dbcScopeIncluded" multiple="multiple" size="12" disabled="disabled"
                    aria-describedby="dbcScopeStatus"></select>
         </div>
      </div>
      <p id="dbcScopeNotice"></p>
      <details id="dbcScopeLockedPanel">
         <summary>Always excluded / unavailable for capture
            <span id="dbcScopeLockedCount"></span></summary>
         <ul id="dbcScopeLocked" class="dbcScopeLocked"></ul>
      </details>
      <div id="dbcScopeStatus" role="status" aria-live="polite">Loading monitoring scope...</div>
   </div>
</s:searchFieldSet>

<br/>

<%--
  The fieldset id must not match any function name in this page's script.
  s:searchFieldSet renders an Ext.form.FieldSet, i.e. a real <fieldset>, and
  begin.jspf has already opened <form name="mainform">. A fieldset is a listed
  form element, so it becomes a named property of that form - and an inline
  onclick resolves identifiers against the owner form before the window. With
  id="dbCaptureSearch" the call dbCaptureSearch() therefore found the fieldset
  element instead of the function and threw, which is why the Search button did
  nothing at all while Clear - no matching id - worked.
--%>
<s:searchFieldSet legend="Find" id="dbCaptureFindPanel" collapsed="false">
   <%--
     maxlength="0" is not "no characters", it is "no limit": TextBoxTag's
     constructor defaults the field to 10, and TextBoxRenderer gates both the
     maxlength attribute and the PTC.wizard.limitChars* handlers on > 0. Left
     unset, a pasted search term of any realistic length was rejected with
     "The number of characters in this attribute value can be no more than 10".
   --%>
   <jca:renderPropertyPanel>
      <w:textBox id="dbcKeyword" name="dbcKeyword" size="60" maxlength="0"
                 propertyLabel="Keyword"/>
   </jca:renderPropertyPanel>
   <div style="padding: 4px 0 8px 0; color: #555;">
      Matches capture id, description, table name, class / API name, column
      name, object identity, user, and old / new values. Leave empty for
      everything.
      <br/>
      A plain word matches anywhere: <tt>wt</tt> finds
      <tt>WTDOCUMENTMASTERKEY</tt>. Add <tt>*</tt> or <tt>?</tt> and it matches
      a whole value instead, so <tt>wt*</tt> is "starts with wt",
      <tt>*master*</tt> is "contains master", and <tt>WTPART?</tt> is one more
      character.
   </div>
   <div style="padding-bottom: 6px;">
      <input type="checkbox" id="dbcShowUnfinished"/>
      <label for="dbcShowUnfinished">Show unfinished captures (still running,
         failed or aborted)</label>
   </div>
   <div>
      <%-- Handlers are attached in the script below with addEventListener,
           never with an inline onclick: an inline handler is evaluated with
           the owner form in its scope chain, so any element id that matches a
           function name silently shadows it. --%>
      <input type="button" id="dbcSearchButton" value="Search"/>
      <input type="button" id="dbcStopButton" value="Stop search" disabled="disabled"
             title="Cancel loading these results; this does not stop DB Capture"/>
      <input type="button" id="dbcClearButton" value="Clear"
             title="Empty the keyword box and the results (does not search)"/>
      <span id="dbcSearchStatus" role="status" aria-live="polite"
            style="margin-left: 10px; color: #555;"></span>
   </div>
   <div id="dbcFilterRow" style="padding-top: 6px; display: none;">
      Database Changes is showing capture
      <b><span id="dbcFilterCapture"></span></b>
      <input type="button" id="dbcAllCapturesButton" value="Show all captures"
             style="margin-left: 8px;"/>
   </div>
</s:searchFieldSet>

<br/>

<jsp:include page="${mvc:getComponentURL('dbcapture.sessionTable')}" flush="true"/>

<div style="padding: 4px 0; color: #555;">
   Click a capture row to filter Database Changes; checkboxes select sessions for toolbar actions.
   Double-click its Description
   cell, or use Edit Description, to edit it (maximum 400 characters).
   Counts in Find describe loaded results; each table's own search can narrow its display further.
</div>
<div id="dbcActionStatus" role="status" aria-live="polite" style="color: #555;"></div>

<br/>

<s:searchFieldSet legend="Hide these tables in Database Changes"
                  id="dbcTableFilterPanel" collapsed="true">
<div class="dbcTableFilter">
   <p>Right-click a Database Changes row to hide its table. Remove a table with
      its &times; button to show it again under the current search conditions.
      This affects only this page's Database Changes display, not Monitoring scope,
      Capture Sessions or saved data.</p>
   <input type="hidden" id="dbcHideTables" value=""/>
   <div id="dbcHiddenHeading"><b>Currently hidden</b></div>
   <div id="dbcHiddenReadback" class="dbcHiddenReadback" role="list"
        aria-labelledby="dbcHiddenHeading">Nothing is hidden.</div>
   <div id="dbcFilterStatus" role="status" aria-live="polite"></div>
</div>
</s:searchFieldSet>

<div id="dbcCsvStatus" class="dbcCsvFeedback" role="status" aria-live="polite"></div>
<jsp:include page="${mvc:getComponentURL('dbcapture.changeTable')}" flush="true"/>

<script type="text/javascript">
   var DBC_SESSION_TABLE = "dbcapture.sessionTable";
   var DBC_CHANGE_TABLE  = "dbcapture.changeTable";
   var DBC_TABLES        = [DBC_SESSION_TABLE, DBC_CHANGE_TABLE];
   var DBC_ENDPOINT      = "netmarkets/jsp/dbcapture/dbCaptureState.jsp";
   var DBC_COMPLETED_CAPTURE = "<%=wt.util.HTMLEncoder.encodeForJavascript(dbcCompletedCapture)%>";

   /**
    * Bumped by every Search, Stop and Clear. A search that finishes after the
    * user has moved on compares its token and stays quiet instead of
    * overwriting the newer state.
    */
   var dbcSearchToken = 0;

   /**
    * Capture whose rows the changes table is restricted to, or null for all.
    *
    * Selecting a session is the normal way to read these results - one capture
    * at a time - so clicking a row sets this and the changes table reloads
    * against it. The builder does the filtering server side, so a capture with
    * thousands of rows does not have to come down to the browser first.
    */
   var dbcCaptureFilter = null;
   var dbcActiveSearch = null;
   var dbcAppliedCriteria = null;
   var dbcPendingFilterRequest = null;
   var dbcFilterRequestToken = 0;
   var dbcDescriptionSaving = false;
   var dbcSessionDeleting = false;
   var dbcCsvSaving = false;
   var dbcCsvAction = null;
   var dbcCsvButton = null;

   function dbCaptureKeyword() {
      var el = document.getElementById("dbcKeyword");
      return el ? el.value.replace(/^\s+|\s+$/g, "") : "";
   }

   function dbcMessage(id, text, isProblem) {
      var el = document.getElementById(id);
      if (el) {
         el.innerHTML = "";
         el.appendChild(document.createTextNode(text || ""));
         el.style.color = isProblem ? "#a00" : "#555";
      }
   }

   function dbcSetStatus(text, isProblem) {
      dbcMessage("dbcSearchStatus", text, isProblem);
   }

   function dbcSetActionStatus(text, isProblem) {
      dbcMessage("dbcActionStatus", text, isProblem);
   }

   function dbcSetCsvDisabled(disabled) {
      if (dbcCsvAction && !dbcCsvAction.destroyed) { dbcCsvAction.setDisabled(disabled); }
      if (dbcCsvButton) {
         dbcCsvButton.disabled = disabled;
         dbcCsvButton.setAttribute("aria-disabled", String(disabled));
      }
   }

   function dbcDescribeCsvControl(grid) {
      var buttons = grid.getEl().dom.querySelectorAll("button");
      for (var i = 0; i < buttons.length; i++) {
         if ((buttons[i].style.backgroundImage || "").indexOf("export_list_to_csv.png") < 0) { continue; }
         dbcCsvButton = buttons[i];
         dbcCsvButton.setAttribute("aria-label", "Export Database Changes as CSV");
         var wrapper = dbcCsvButton.closest(".x-btn");
         var action = wrapper && Ext.getCmp(wrapper.id);
         if (action && typeof action.setDisabled === "function") { dbcCsvAction = action; }
         dbcSetCsvDisabled(!!(dbcCsvSaving || dbcActiveSearch || dbcPendingFilterRequest));
      }
   }

   function dbcDownloadCsv(event, action) {
      if (!dbcCsvAction && action && typeof action.setDisabled === "function") {
         dbcCsvAction = action;
      }
      if (dbcCsvSaving || dbcActiveSearch || dbcPendingFilterRequest) {
         dbcMessage("dbcCsvStatus", "Wait for the current search or download to finish.", true);
         return false;
      }
      var snapshot;
      try {
         if (!window.DbCaptureCsv) { throw new Error("CSV support did not load. Reload this page."); }
         snapshot = window.DbCaptureCsv.snapshotGrid(dbcTable(DBC_CHANGE_TABLE));
      } catch (error) {
         dbcMessage("dbcCsvStatus", error.message, true);
         return false;
      }
      dbcCsvSaving = true;
      dbcSetCsvDisabled(true);
      dbcMessage("dbcCsvStatus", "Preparing CSV for " + snapshot.rows.length + " displayed row(s)...");
      dbcPostJson("op=exportNames&captureIds=" + encodeURIComponent(snapshot.captureIds.join(",")),
            function (problem, data) {
         dbcCsvSaving = false;
         dbcSetCsvDisabled(!!(dbcActiveSearch || dbcPendingFilterRequest));
         if (problem) {
            dbcMessage("dbcCsvStatus", "CSV was not downloaded: " + problem, true);
            return;
         }
         try {
            var name = window.DbCaptureCsv.download(snapshot, data.exportNames);
            dbcMessage("dbcCsvStatus", "CSV download: " + name + " (" + snapshot.rows.length
                  + " row(s), " + snapshot.headers.length + " visible column(s)).");
         } catch (error) {
            dbcMessage("dbcCsvStatus", "CSV was not downloaded: " + error.message, true);
         }
      });
      return false;
   }

   function dbcPostJson(body, done) {
      var pageControl = document.getElementById("dbcSearchButton");
      var req = new XMLHttpRequest();
      var settled = false;
      function finish(problem, data) {
         if (settled) { return; }
         settled = true;
         if (document.getElementById("dbcSearchButton") !== pageControl) { return; }
         done(problem, data);
      }
      req.onreadystatechange = function () {
         if (req.readyState !== 4 || req.status === 0) { return; }
         var data;
         try {
            data = JSON.parse(req.responseText);
         } catch (e) {
            finish("Unreadable server response (HTTP " + req.status + ").", null);
            return;
         }
         if (req.status < 200 || req.status >= 300 || !data || data.ok !== true) {
            finish(data && data.message ? data.message
                  : "The request failed (HTTP " + req.status + ").", null);
            return;
         }
         finish(null, data);
      };
      req.onerror = function () {
         finish("Could not reach DB Capture. Check the connection before retrying.", null);
      };
      req.ontimeout = function () {
         finish("The request timed out. Reload its state before retrying; a save may have completed.", null);
      };
      req.onabort = function () { finish("Request cancelled.", null); };
      try {
         req.open("POST", DBC_ENDPOINT, true);
         req.timeout = 30000;
         req.setRequestHeader("Content-Type",
                              "application/x-www-form-urlencoded; charset=UTF-8");
         req.send(body);
      } catch (e) {
         finish("Could not send the request: " + e.message, null);
      }
      return req;
   }

   function dbcSetBusy(busy) {
      var search = document.getElementById("dbcSearchButton");
      var stop = document.getElementById("dbcStopButton");
      if (search) { search.disabled = busy; }
      if (stop) { stop.disabled = !busy; }
      if (busy && !dbcCsvSaving) { dbcMessage("dbcCsvStatus", ""); }
      dbcSetCsvDisabled(busy || dbcCsvSaving);
      var buttons = document.querySelectorAll("#dbcHiddenReadback button");
      for (var i = 0; i < buttons.length; i++) { buttons[i].disabled = busy; }
   }

   /**
    * The Ext grid behind a component id, or null while the page is still
    * building itself.
    *
    * PTC.jca.table.Utils.getTable returns null in that window and
    * addSubmitFormParam calls getStore() on the result without checking, so
    * calling it too early throws and kills the click silently. Everything here
    * goes through this accessor instead.
    */
   function dbcTable(id) {
      try {
         var table = PTC.jca.table.Utils.getTable(id);
         if (table) {
            return table;
         }
         // getTable matches the grid id exactly. If the framework ever ids the
         // grid with a prefix or suffix around the component id, find it by
         // scanning rather than reporting the table as absent.
         var tables = PTC.jca.table.Utils.getTables() || [];
         for (var i = 0; i < tables.length; i++) {
            if (tables[i] && tables[i].id
                  && tables[i].id.indexOf(id) !== -1) {
               return tables[i];
            }
         }
         return null;
      } catch (e) {
         return null;
      }
   }

   /* ---- which tables to leave out of Database Changes ------------------- */

   function dbcHiddenGroups() {
      return [];
   }

   function dbcHiddenTables() {
      var el = document.getElementById("dbcHideTables");
      return el ? el.value : "";
   }

   function dbcExactTableNames(raw) {
      var names = [];
      var seen = {};
      var tokens = String(raw || "").split(",");
      for (var i = 0; i < tokens.length; i++) {
         var name = tokens[i].replace(/^\s+|\s+$/g, "").toUpperCase();
         if (!name) { continue; }
         if (!/^[A-Z0-9_$#]+$/.test(name)) {
            throw new Error("Not an exact Oracle table name: " + name);
         }
         if (!seen[name]) {
            seen[name] = true;
            names.push(name);
         }
      }
      return names;
   }

   function dbcAddHiddenTable(tableName) {
      var input = document.getElementById("dbcHideTables");
      var name = String(tableName || "").replace(/^\s+|\s+$/g, "").toUpperCase();
      if (!input || !/^[A-Z0-9_$#]+$/.test(name)) {
         dbcSetFilterStatus("Could not hide an invalid table name.", true);
         return;
      }
      if (dbcActiveSearch || dbcPendingFilterRequest) {
         dbcSetFilterStatus("Wait for the current search to finish before hiding a table.", true);
         return;
      }
      var names;
      try {
         names = dbcExactTableNames(input.value);
      } catch (e) {
         dbcSetFilterStatus(e.message, true);
         return;
      }
      if (names.indexOf(name) < 0) {
         names.push(name);
      } else {
         dbcSetFilterStatus(name + " is already in Currently hidden.");
         return;
      }
      input.value = names.join(", ");
      dbcApplyHiddenFilter();
   }

   function dbcRemoveHiddenTable(tableName) {
      if (dbcActiveSearch || dbcPendingFilterRequest) {
         dbcSetFilterStatus("Wait for the current search to finish before showing a table.", true);
         return;
      }
      var input = document.getElementById("dbcHideTables");
      var names = dbcExactTableNames(input.value);
      input.value = names.filter(function (name) { return name !== tableName; }).join(", ");
      dbcApplyHiddenFilter();
   }

   function dbcApplyHiddenFilter() {
      var criteria = dbcAppliedCriteria ? dbcCopyCriteria(dbcAppliedCriteria) : dbcCriteria();
      criteria.tables = dbcExactTableNames(dbcHiddenTables()).join(", ");
      criteria.groups = "";
      criteria.captureId = dbcCaptureFilter;
      criteria.hiddenEdit = true;
      dbcRunSearch([DBC_CHANGE_TABLE], criteria);
   }

   /*
    * Note the name. The checkbox is id="dbcShowUnfinished", and element ids
    * on this page must not collide with function names - see the comment on
    * the wiring block at the bottom for what that collision did to the Search
    * button.
    */
   function dbcUnfinishedWanted() {
      var el = document.getElementById("dbcShowUnfinished");
      return !!(el && el.checked);
   }

   function dbcSetFilterStatus(text, isProblem) {
      dbcMessage("dbcFilterStatus", text, isProblem);
   }

   function dbcFillReadback(names) {
      var box = document.getElementById("dbcHiddenReadback");
      if (!box) { return; }
      box.innerHTML = "";
      if (!names || names.length === 0) {
         box.appendChild(document.createTextNode("Nothing is hidden."));
         return;
      }
      names.forEach(function (name) {
         var chip = document.createElement("span");
         chip.className = "dbcHiddenChip";
         chip.setAttribute("role", "listitem");
         chip.appendChild(document.createTextNode(name));
         var remove = document.createElement("button");
         remove.type = "button";
         remove.className = "dbcHiddenRemove";
         remove.textContent = "\u00d7";
         remove.title = "Show " + name + " again";
         remove.setAttribute("aria-label", "Show " + name + " again");
         remove.disabled = !!(dbcActiveSearch || dbcPendingFilterRequest);
         remove.addEventListener("click", function () { dbcRemoveHiddenTable(name); }, false);
         chip.appendChild(remove);
         box.appendChild(chip);
      });
   }

   function dbcCriteria() {
      return {
         keyword: dbCaptureKeyword(),
         groups: dbcHiddenGroups().join(","),
         tables: dbcExactTableNames(dbcHiddenTables()).join(", "),
         unfinished: dbcUnfinishedWanted(),
         captureId: dbcCaptureFilter
      };
   }

   function dbcCopyCriteria(criteria) {
      var copy = {};
      for (var key in criteria) {
         if (criteria.hasOwnProperty(key)) { copy[key] = criteria[key]; }
      }
      return copy;
   }

   function dbcReportFilter(onResolved, options) {
      options = options || {};
      var criteria;
      try {
         criteria = options.criteria || dbcCriteria();
      } catch (e) {
         dbcSetFilterStatus(e.message + ". The filter was not applied.", true);
         if (onResolved) { onResolved(null); }
         return null;
      }
      var requestToken = ++dbcFilterRequestToken;
      var body = "op=tables"
         + "&groups=" + encodeURIComponent(criteria.groups)
         + "&tables=" + encodeURIComponent(criteria.tables)
         + "&showUnfinished=" + (criteria.unfinished ? "true" : "false")
         + (criteria.captureId ? "&captureId=" + encodeURIComponent(criteria.captureId) : "");
      return dbcPostJson(body, function (problem, data) {
         if (requestToken !== dbcFilterRequestToken) { return; }
         if (problem) {
            dbcSetFilterStatus("Could not check the table filter: " + problem, true);
            if (onResolved) { onResolved(null); }
            return;
         }
         if (!Array.isArray(data.known) || !Array.isArray(data.hidden)
               || !Array.isArray(data.unknown)
               || (data.available !== undefined && !Array.isArray(data.available))) {
            dbcSetFilterStatus("Could not check the table filter: incomplete table metadata.", true);
            if (onResolved) { onResolved(null); }
            return;
         }

         var known = data.known || [];
         var hidden = data.hidden || [];
         var unknown = data.unknown || [];
         if (options.publish !== false) {
            dbcFillReadback(dbcExactTableNames(criteria.tables));
            if (known.length === 0) {
               dbcSetFilterStatus("No saved database tables in this capture selection.", false);
            } else if (hidden.length === 0) {
               dbcSetFilterStatus("Showing every table that was captured.", false);
            } else {
               dbcSetFilterStatus("Hiding " + hidden.length + " of "
                  + known.length + " captured tables; they are listed below.", false);
            }
            if (unknown.length) {
               dbcSetFilterStatus("These hidden selections have no remaining saved rows: "
                     + unknown.join(", ") + ". They can still be removed with \u00d7.");
            }
         }
         if (onResolved) { onResolved(data); }
      });
   }

   /** How many rows a table is currently holding. */
   function dbcRowCount(id) {
      var table = dbcTable(id);
      if (!table) {
         return 0;
      }
      var store = table.getStore();
      if (!store) {
         return 0;
      }
      return store.getRecordsCount ? store.getRecordsCount() : store.getCount();
   }

   /**
    * Load the given tables against the current keyword and capture filter.
    *
    * One code path for all four reasons the tables reload - Search, picking a
    * capture, going back to all captures, and a delete - so the completion
    * handling and the reporting cannot drift apart between them.
    */
   function dbcClearTables(tableIds) {
      for (var i = 0; i < tableIds.length; i++) {
         var grid = dbcTable(tableIds[i]);
         if (!grid) { continue; }
         var selection = grid.getSelectionModel ? grid.getSelectionModel() : null;
         if (selection && selection.clearSelections) { selection.clearSelections(); }
         var store = grid.getStore();
         store.totalLength = 0;
         store.removeAll();
         store.load_complete = true;
         store.moreData = false;
         if (grid.jcaTableConfig) {
            grid.jcaTableConfig.pageOffset = 0;
            grid.jcaTableConfig.totalRows = 0;
         }
         // Windchill's removeAll fires clear, but its title counters listen to datachanged.
         store.fireEvent("datachanged", store);
      }
   }

   function dbcCancelLoading(tableIds) {
      var problems = [];
      for (var i = 0; i < tableIds.length; i++) {
         var grid = dbcTable(tableIds[i]);
         var store = grid && grid.getStore ? grid.getStore() : null;
         if (!store || (store.load_complete !== false && store.loading !== true
               && !(store.isLoading && store.isLoading()))) { continue; }
         try {
            var button = Ext.getCmp("header_" + grid.id + "_cancelDataSourceButton");
            if (button && button.cancelButtonHandler) {
               button.cancelButtonHandler();
            } else if (store.chunk && store.chunk.id && PTC.jca.DataSourceRegistry) {
               store.cancelBtnClicked = true;
               PTC.jca.DataSourceRegistry.killDataSource(store, [store.chunk.id]);
            } else {
               throw new Error("No cancellable data source for " + grid.id);
            }
         } catch (e) {
            problems.push(e.message || String(e));
         }
      }
      return problems.length ? problems.join("; ") : null;
   }

   function dbcCancelSearchWork(tableIds) {
      dbcFilterRequestToken++;
      if (dbcPendingFilterRequest && dbcPendingFilterRequest.readyState !== 4) {
         dbcPendingFilterRequest.abort();
      }
      dbcPendingFilterRequest = null;
      var job = dbcActiveSearch;
      dbcActiveSearch = null;
      var ids = tableIds ? tableIds.slice(0) : [];
      if (job) {
         job.cleanup();
         for (var i = 0; i < job.tableIds.length; i++) {
            if (ids.indexOf(job.tableIds[i]) < 0) { ids.push(job.tableIds[i]); }
         }
      }
      var problem = dbcCancelLoading(ids);
      if (job && !problem) { dbcClearTables(job.tableIds); }
      return problem;
   }

   function dbcStoreHasCapture(store, captureId) {
      var found = false;
      var records = store.allData || store.snapshot || store.data;
      var inspect = function (record) {
         if (String(record.get("captureId")) === captureId) { found = true; }
      };
      if (records && records.each) { records.each(inspect); }
      else { store.each(inspect); }
      return found;
   }

   function dbcRunSearch(tableIds, criteria, completedCapture) {
      var token = ++dbcSearchToken;
      var pageControl = document.getElementById("dbcSearchButton");
      var cancelProblem = dbcCancelSearchWork(tableIds);
      if (cancelProblem) {
         dbcSetBusy(false);
         dbcSetStatus("Could not cancel the previous search: " + cancelProblem, true);
         return;
      }
      if (!criteria) {
         try {
            criteria = dbcCriteria();
         } catch (e) {
            dbcSetBusy(false);
            dbcSetFilterStatus(e.message + ". The filter was not applied.", true);
            dbcSetStatus("Search was not started. Correct the Hidden filter.", true);
            return;
         }
         criteria.awaitingCapture = completedCapture || null;
         var rawTables = dbcHiddenTables();
         dbcSetBusy(true);
         dbcSetStatus("Checking display filters...");
         dbcPendingFilterRequest = dbcReportFilter(function (data) {
            if (token !== dbcSearchToken) { return; }
            dbcPendingFilterRequest = null;
            if (!data) {
               dbcSetBusy(false);
               dbcSetStatus("Search was not applied. Correct or retry the Hidden filter.", true);
               return;
            }
            var input = document.getElementById("dbcHideTables");
            if (input && input.value === rawTables) { input.value = criteria.tables; }
            dbcRunSearch(tableIds, criteria);
         }, {criteria: criteria, publish: false});
         return;
      }

      criteria = dbcCopyCriteria(criteria);
      var entries = [];
      for (var i = 0; i < tableIds.length; i++) {
         var grid = dbcTable(tableIds[i]);
         if (!grid || !grid.getStore()) {
            dbcSetBusy(false);
            dbcSetStatus("The result tables are not ready yet. Press Search to retry.", true);
            return;
         }
         entries.push({grid: grid, store: grid.getStore(), done: false, listeners: []});
      }
      var pending = entries.length;
      var reported = false;
      var startedAt = new Date().getTime();
      var emptyReportChecks = 0;
      var emptyReportLimit = criteria.awaitingCapture ? 400 : 10;
      var job = {tableIds: tableIds.slice(0), timer: null, nextReport: null};
      job.cleanup = function () {
         clearTimeout(job.timer);
         clearTimeout(job.nextReport);
         for (var j = 0; j < entries.length; j++) {
            var entry = entries[j];
            for (var k = 0; k < entry.listeners.length; k++) {
               entry.store.un(entry.listeners[k].name, entry.listeners[k].handler);
            }
            entry.listeners = [];
         }
      };
      dbcActiveSearch = job;
      function current() {
         return !reported && token === dbcSearchToken
               && document.getElementById("dbcSearchButton") === pageControl;
      }
      function finish() {
         reported = true;
         job.cleanup();
         if (dbcActiveSearch === job) { dbcActiveSearch = null; }
         dbcSetBusy(false);
      }
      function fail(message) {
         if (!current()) { return; }
         finish();
         var problem = dbcCancelLoading(tableIds);
         dbcClearTables(tableIds);
         if (criteria.hiddenEdit) {
            var prior = dbcAppliedCriteria ? dbcAppliedCriteria.tables : "";
            document.getElementById("dbcHideTables").value = prior;
            dbcFillReadback(dbcExactTableNames(prior));
            dbcSetFilterStatus("The display change failed; the previous hidden selection was retained.", true);
         }
         dbcSetStatus(message + (problem ? " Cancellation failed: " + problem : ""), true);
      }
      function later() {
         clearTimeout(job.nextReport);
         job.nextReport = setTimeout(report, 100);
      }
      function report() {
         if (!current()) { return; }
         if (new Date().getTime() - startedAt >= 120000) {
            fail("Search timed out after two minutes. Incomplete results were cleared. Press Search to retry.");
            return;
         }
         if (pending > 0) { later(); return; }
         for (var j = 0; j < entries.length; j++) {
            var store = entries[j].store;
            if (store.loading === true || (store.isLoading && store.isLoading())
                  || store.load_complete === false) { later(); return; }
         }
         var sessions = dbcRowCount(DBC_SESSION_TABLE);
         var changes = dbcRowCount(DBC_CHANGE_TABLE);
         if (sessions === 0 && changes === 0 && emptyReportChecks++ < emptyReportLimit) {
            if (criteria.awaitingCapture && emptyReportChecks === 100) {
               dbcSetStatus("Results for " + criteria.awaitingCapture
                     + " are still being applied to the tables...");
            }
            later();
            return;
         }
         finish();
         for (var j = 0; j < entries.length; j++) {
            entries[j].store.fireEvent("datachanged", entries[j].store);
         }
         if (criteria.captureId && tableIds.indexOf(DBC_SESSION_TABLE) >= 0
               && !dbcStoreHasCapture(dbcTable(DBC_SESSION_TABLE).getStore(), criteria.captureId)) {
            dbcCaptureFilter = null;
            dbcShowFilterRow();
            criteria.captureId = null;
            criteria.awaitingCapture = null;
            dbcRunSearch([DBC_CHANGE_TABLE], criteria);
            return;
         }
         dbcAppliedCriteria = dbcCopyCriteria(criteria);
         dbcAppliedCriteria.awaitingCapture = null;
         dbcAppliedCriteria.hiddenEdit = false;
         dbcFillReadback(dbcExactTableNames(criteria.tables));
         dbcReportFilter(null, {criteria: criteria});
         if (sessions === 0 && changes === 0) {
            dbcSetStatus(criteria.awaitingCapture
                  ? "Results for " + criteria.awaitingCapture + " did not appear. Press Search to retry."
                  : "No matches. Check the keyword and Hidden filters.", !!criteria.awaitingCapture);
         } else if (changes === 0 && criteria.captureId) {
            dbcSetStatus("No changes are visible for " + criteria.captureId
                  + ". Check the keyword, hidden-table filters and capture warnings.");
         } else {
            dbcSetStatus(sessions + " capture(s), " + changes + " change row(s) loaded"
                  + (criteria.captureId ? " in " + criteria.captureId : "") + "."
                  + (dbCaptureKeyword() !== criteria.keyword
                     ? " Keyword edited; press Search to apply it." : ""));
         }
      }
      dbcSetBusy(true);
      dbcSetStatus("Searching...");
      job.timer = setTimeout(report, 120000);
      for (var i = 0; i < entries.length; i++) {
         (function (entry) {
            var events = [entry.store.chunkEnabled ? "datasourcecomplete" : "load",
                          "exception", "loadexception", "DSLoadingInterupted"];
            for (var e = 0; e < events.length; e++) {
               var handler = (function (isError) {
                  return function () {
                     if (entry.done || !current()) { return; }
                     entry.done = true;
                     if (isError) {
                        fail("The search could not finish loading "
                              + (entry.grid.id === DBC_SESSION_TABLE ? "Capture Sessions" : "Database Changes")
                              + ". Incomplete results were cleared. Press Search to retry.");
                        return;
                     }
                     pending--;
                     if (pending === 0) { report(); }
                  };
               })(e !== 0);
               entry.listeners.push({name: events[e], handler: handler});
               entry.store.on(events[e], handler);
            }
         })(entries[i]);
      }
      for (var i = 0; i < entries.length && current(); i++) {
         var entry = entries[i];
         var params = {
            dbcKeyword: criteria.keyword, dbcSearched: "true", clearCache: "True",
            dbcHideGroups: criteria.groups, dbcHideTables: criteria.tables,
            dbcShowUnfinished: criteria.unfinished ? "true" : "false"
         };
         if (entry.grid.id === DBC_CHANGE_TABLE && criteria.captureId) {
            params.dbcCaptureId = criteria.captureId;
         }
         entry.store.setBaseParam("submitForm", true);
         try {
            PTC.jca.table.Utils.reload(entry.grid, params, true);
         } catch (e) {
            fail("Could not ask the server for results: " + e.message);
         }
      }
   }

   /** Search button: load both tables. */
   function dbCaptureSearch(completedCapture) {
      dbcRunSearch(DBC_TABLES, null,
            typeof completedCapture === "string" ? completedCapture : null);
   }

   function dbcAutoSearchCompleted(captureId) {
      if (!captureId) { return; }
      if (!/^CAP-[0-9]+$/.test(captureId)) {
         dbcSetStatus("Invalid completed capture ID; automatic search was not started.");
         return;
      }
      var keyword = document.getElementById("dbcKeyword");
      var searchButton = document.getElementById("dbcSearchButton");
      if (!keyword || !searchButton) {
         dbcSetStatus("The search controls are not ready. Reload the page to show the completed capture.");
         return;
      }
      keyword.value = captureId;
      dbcCaptureFilter = captureId;
      dbcShowFilterRow();
      dbcSetStatus("Loading results for " + captureId + "...");
      var token = dbcSearchToken;
      var attempts = 0;
      var timer = setInterval(function () {
         if (document.getElementById("dbcSearchButton") !== searchButton || token !== dbcSearchToken) {
            clearInterval(timer);
            return;
         }
         var ready = true;
         for (var i = 0; i < DBC_TABLES.length; i++) {
            var grid = dbcTable(DBC_TABLES[i]);
            var store = grid && grid.getStore ? grid.getStore() : null;
            if (!store || (typeof store.isLoading === "function" && store.isLoading())
                  || store.loading === true || store.load_complete === false) {
               ready = false;
               break;
            }
         }
         if (ready) {
            clearInterval(timer);
            dbCaptureSearch(captureId);
         } else if (++attempts >= 480) {
            clearInterval(timer);
            dbcSetStatus("Automatic search could not start because the result tables are not ready."
                  + " Press Search to retry.");
         }
      }, 250);
   }

   /** Reload only the changes table, e.g. after picking a capture. */
   function dbcReloadChanges() {
      var criteria = dbcAppliedCriteria ? dbcCopyCriteria(dbcAppliedCriteria) : null;
      if (criteria) { criteria.captureId = dbcCaptureFilter; }
      dbcRunSearch([DBC_CHANGE_TABLE], criteria);
   }

   /* ---- restricting the changes to one capture -------------------------- */

   function dbcShowFilterRow() {
      var row = document.getElementById("dbcFilterRow");
      var name = document.getElementById("dbcFilterCapture");
      if (name) {
         name.innerHTML = "";
         name.appendChild(document.createTextNode(dbcCaptureFilter || ""));
      }
      if (row) {
         row.style.display = dbcCaptureFilter ? "" : "none";
      }
   }

   /** Restrict the changes table to one capture, or to all when null. */
   function dbcSetCaptureFilter(captureId) {
      var wanted = captureId || null;
      if (wanted === dbcCaptureFilter) {
         return;
      }
      dbcCaptureFilter = wanted;
      dbcShowFilterRow();
      dbcReloadChanges();
   }

   /**
    * Make a clicked row the framework's selection too.
    *
    * The toolbar actions are declared selectRequired, and the framework reads
    * its selection from the grid's checkbox column - not from the row
    * highlight. Clicking a row highlighted it and set this page's capture
    * filter, but left the checkbox clear, so Edit Description was launched
    * with no oid and the popup reported that nothing was selected.
    *
    * PTC.jca.table.Utils.selectRow keeps any existing selection, and Edit
    * Description is multiselect="false", so clear first: one click means one
    * selected session.
    */
   function dbcSelectRow(grid, rowIndex) {
      try {
         var record = grid.getStore().getAt(rowIndex);
         var selection =
            grid.getSelectionModel ? grid.getSelectionModel() : null;
         if (!record || !selection || !selection.clearSelections) {
            return;
         }
         selection.clearSelections();
         PTC.jca.table.Utils.selectRow(grid, record, true);
      } catch (e) {
         // Fall through - the checkbox is still there to tick by hand, and a
         // broken page helps nobody.
      }
   }

   function dbcSessionRowClick(grid, rowIndex, event) {
      var checker = event && event.getTarget && event.getTarget(".x-grid3-row-checker");
      if (checker || (event && (event.ctrlKey || event.metaKey || event.shiftKey))) { return; }
      dbcSelectRow(grid, rowIndex);
      dbcSetCaptureFilter(dbcRowCaptureId(grid, rowIndex));
   }

   function dbcEditDescriptionCell(grid, rowIndex, columnIndex, event) {
      if (grid.getColumnModel().getDataIndex(columnIndex) !== "description") { return; }
      if (event && event.stopEvent) { event.stopEvent(); }
      dbcEditDescriptionFromMenu(dbcRecordValue(grid, rowIndex, "oid"),
            dbcRowCaptureId(grid, rowIndex), dbcRecordValue(grid, rowIndex, "description"));
      return false;
   }

   function dbcDescribeEditControls(grid) {
      var column = grid.getColumnModel().findColumnIndex("description");
      if (column >= 0) {
         for (var i = 0; i < grid.getStore().getCount(); i++) {
            var cell = grid.getView().getCell(i, column);
            if (cell) {
               cell.title = "Double-click to edit this description (maximum 400 characters)";
               cell.style.cursor = "text";
            }
         }
      }
      var buttons = grid.getEl().dom.querySelectorAll("button");
      for (var b = 0; b < buttons.length; b++) {
         var image = buttons[b].style.backgroundImage || "";
         var label = image.indexOf("edit.gif") >= 0 ? "Edit Description"
               : image.indexOf("delete.gif") >= 0 ? "Delete Capture Session" : null;
         if (label) {
            buttons[b].setAttribute("aria-label", label);
            buttons[b].title = label;
         }
      }
   }

   /**
    * The capture id of a clicked row.
    *
    * Reads the store record first, because that is the actual data. Falls back
    * to the rendered cell so that a change in how the store names its fields
    * cannot silently stop the filter working.
    */
   function dbcRowCaptureId(grid, rowIndex) {
      try {
         var record = grid.getStore().getAt(rowIndex);
         if (record) {
            var value = record.get ? record.get("captureId") : null;
            if (value === null || value === undefined) {
               value = record.data ? record.data.captureId : null;
            }
            if (value !== null && value !== undefined && value !== "") {
               return String(value).replace(/<[^>]*>/g, "").trim();
            }
         }
         var columns = grid.getColumnModel();
         for (var c = 0; c < columns.getColumnCount(); c++) {
            if (columns.getDataIndex(c) === "captureId") {
               var cell = grid.getView().getCell(rowIndex, c);
               if (cell) {
                  return (cell.textContent || cell.innerText || "").trim();
               }
            }
         }
      } catch (e) {
         // Fall through - no filter is better than a broken page.
      }
      return null;
   }

   function dbcRecordValue(grid, rowIndex, name) {
      try {
         var record = grid.getStore().getAt(rowIndex);
         var value = record && record.get ? record.get(name)
               : record && record.data ? record.data[name] : null;
         if (name === "description" && value !== null && value !== undefined) {
            return PTC.util.unescapeHTML(String(value));
         }
         return value === null || value === undefined
               ? null : String(value).replace(/<[^>]*>/g, "").trim();
      } catch (e) {
         return null;
      }
   }

   function dbcSessionOidNumber(oid) {
      var match = /^OR:com\.ptc\.dbcapture\.DbCaptureSession:([0-9]+)$/.exec(oid || "");
      return match ? match[1] : null;
   }

   function dbcSelectContextRow(grid, rowIndex) {
      try {
         var selection = grid.getSelectionModel ? grid.getSelectionModel() : null;
         if (!selection || !selection.selectRow) {
            return;
         }
         selection.clearSelections();
         selection.selectRow(rowIndex, false);
      } catch (e) {
         dbcSetStatus("Could not select the row for its context menu.");
      }
   }

   function dbcEditDescriptionFromMenu(oid, captureId, currentDescription, previousProblem) {
      if (dbcDescriptionSaving || dbcSessionDeleting) {
         dbcSetActionStatus("Wait for the current capture edit or deletion to finish.", true);
         return;
      }
      var sessionOid = dbcSessionOidNumber(oid);
      if (!sessionOid) {
         dbcSetActionStatus("Could not edit the selected capture: invalid session identity.", true);
         return;
      }
      Ext.MessageBox.prompt("Edit Description",
            (previousProblem ? previousProblem + "<br/>" : "")
                  + "Description for " + captureId + " (maximum 400 characters)",
            function (button, value) {
               if (button !== "ok") { return; }
               if (dbcDescriptionSaving || dbcSessionDeleting) { return; }
               if ((value || "").length > 400) {
                  dbcSetActionStatus("Description is limited to 400 characters. Nothing was saved.", true);
                  dbcEditDescriptionFromMenu(oid, captureId, value,
                        "Description is limited to 400 characters. Nothing was saved.");
                  return;
               }
               dbcDescriptionSaving = true;
               dbcSetActionStatus("Saving the description for " + captureId + "...");
               dbcPostJson("op=describe&sessionOid=" + encodeURIComponent(sessionOid)
                     + "&description=" + encodeURIComponent(value || ""), function (problem, data) {
                  dbcDescriptionSaving = false;
                  if (problem) {
                     dbcSetActionStatus("Could not confirm the description save: " + problem
                           + " Your draft is retained in the editor.", true);
                     dbcEditDescriptionFromMenu(oid, captureId, value,
                           "Could not confirm the save. Check the status message before retrying.");
                     return;
                  }
                  dbcSetActionStatus("Description saved for " + captureId + ".");
                  dbcRunSearch(DBC_TABLES);
               });
            }, null, false, currentDescription || "");
      if (Ext.MessageBox.getDialog) {
         var dialog = Ext.MessageBox.getDialog();
         var input = dialog.getEl().dom.querySelector('input[type="text"]');
         if (input) {
            input.maxLength = 400;
            input.setAttribute("aria-label", "Description (maximum 400 characters)");
         }
      }
   }

   function dbcDeleteSessionFromMenu(oid, captureId) {
      if (dbcDescriptionSaving || dbcSessionDeleting) {
         dbcSetActionStatus("Wait for the current capture edit or deletion to finish.", true);
         return;
      }
      var sessionOid = dbcSessionOidNumber(oid);
      if (!sessionOid) {
         dbcSetActionStatus("Could not delete the selected capture: invalid session identity.", true);
         return;
      }
      if (!window.confirm("Delete " + captureId
            + " and all of its saved database changes and diagnostics?")) {
         return;
      }
      dbcSessionDeleting = true;
      dbcSetActionStatus("Deleting " + captureId + "...");
      dbcPostJson("op=delete&sessionOid=" + encodeURIComponent(sessionOid), function (problem, data) {
         dbcSessionDeleting = false;
         if (problem) {
            dbcSetActionStatus("Could not confirm deletion: " + problem, true);
            return;
         }
         if (dbcCaptureFilter === captureId) {
            dbcCaptureFilter = null;
            dbcShowFilterRow();
         }
         var keyword = document.getElementById("dbcKeyword");
         if (keyword && dbCaptureKeyword() === captureId) { keyword.value = ""; }
         var completion = document.getElementById("dbcCompletionStatus");
         if (completion && DBC_COMPLETED_CAPTURE === captureId) { completion.style.display = "none"; }
         dbcSetActionStatus("Capture session " + captureId + " and its saved results were deleted.");
         dbcRunSearch(DBC_TABLES);
      });
   }

   function dbcShowSessionContextMenu(grid, rowIndex, xy) {
      dbcSelectContextRow(grid, rowIndex);
      var captureId = dbcRowCaptureId(grid, rowIndex);
      var oid = dbcRecordValue(grid, rowIndex, "oid");
      var description = dbcRecordValue(grid, rowIndex, "description");
      if (!captureId || !dbcSessionOidNumber(oid)) {
         dbcSetStatus("Could not read the selected capture session.");
         return;
      }
      dbcSetCaptureFilter(captureId);
      var headerState = window.DbCaptureHeader && window.DbCaptureHeader.getState
            ? window.DbCaptureHeader.getState() : null;
      var menu = new Ext.menu.Menu({
         items: [{
            text: "Edit Description",
            disabled: dbcDescriptionSaving || dbcSessionDeleting,
            handler: function () {
               dbcEditDescriptionFromMenu(oid, captureId, description);
            }
         }, {
            text: "Delete Capture Session",
            disabled: dbcDescriptionSaving || dbcSessionDeleting
                  || (headerState && headerState.running && headerState.captureId === captureId),
            handler: function () { dbcDeleteSessionFromMenu(oid, captureId); }
         }]
      });
      menu.on("hide", function () { menu.destroy(); });
      menu.showAt(xy);
   }

   function dbcInstallCapturedContextMenu(grid, showMenu) {
      var element = grid.getEl ? grid.getEl() : null;
      var dom = element && element.dom;
      if (!dom || !dom.addEventListener) {
         dbcSetStatus("This browser cannot install DB Capture context menus.");
         return;
      }
      function rowIndex(event) {
         return grid.getView().findRowIndex(event.target || event.srcElement);
      }
      function suppress(event) {
         event.preventDefault();
         event.returnValue = false;
         event.stopPropagation();
         if (event.stopImmediatePropagation) { event.stopImmediatePropagation(); }
      }
      dom.addEventListener("mousedown", function (event) {
         if (event.button !== 2 && event.which !== 3) {
            return;
         }
         // Opening here can retarget the later contextmenu to the floating menu.
         suppress(event);
      }, true);
      dom.addEventListener("contextmenu", function (event) {
         // Match OOTB: cancel the native event before a menu can take focus.
         suppress(event);
         var index = rowIndex(event);
         if (index === false || index < 0) {
            return;
         }
         showMenu(grid, index,
               [event.pageX || event.clientX, event.pageY || event.clientY]);
      }, true);
      dom.oncontextmenu = function () { return false; };
   }

   function dbcInstallDocumentContextSuppression() {
      if (window.dbcRestoreDocumentContextSuppression) {
         window.dbcRestoreDocumentContextSuppression();
      }
      var previous = document.oncontextmenu;
      function insideResultGrid(target) {
         for (var i = 0; i < DBC_TABLES.length; i++) {
            var grid = dbcTable(DBC_TABLES[i]);
            var element = grid && grid.getEl ? grid.getEl() : null;
            if (element && element.dom && element.dom.contains(target)) {
               return true;
            }
         }
         return false;
      }
      function suppressBrowserMenu(event) {
         var ev = event || window.event;
         if (ev && insideResultGrid(ev.target || ev.srcElement)) {
            if (ev.preventDefault) { ev.preventDefault(); }
            ev.returnValue = false;
            return false;
         }
         return typeof previous === "function"
               ? previous.call(document, ev) : true;
      }
      document.oncontextmenu = suppressBrowserMenu;
      function restore() {
         if (document.oncontextmenu === suppressBrowserMenu) {
            document.oncontextmenu = previous;
         }
         if (window.removeEventListener) { window.removeEventListener("unload", restore, false); }
         if (window.dbcRestoreDocumentContextSuppression === restore) {
            delete window.dbcRestoreDocumentContextSuppression;
         }
      }
      window.dbcRestoreDocumentContextSuppression = restore;
      if (window.addEventListener) {
         window.addEventListener("unload", restore, false);
      }
   }

   function dbcShowChangeContextMenu(grid, rowIndex, event) {
      var tableName = dbcRecordValue(grid, rowIndex, "tableName");
      if (!tableName || !/^[A-Z0-9_$#]+$/.test(tableName.toUpperCase())) {
         dbcSetFilterStatus("Could not read the selected table name.", true);
         return;
      }
      tableName = tableName.toUpperCase();
      event.stopEvent();
      var menu = new Ext.menu.Menu({
         items: [{
            text: "Hide " + tableName + " in Database Changes",
            handler: function () { dbcAddHiddenTable(tableName); }
         }]
      });
      menu.on("hide", function () { menu.destroy(); });
      menu.showAt(event.getXY());
   }

   /** Cancel result loading through Windchill's standard table cancellation API. */
   function dbCaptureStopSearch() {
      dbcSearchToken++;
      var problem = dbcCancelSearchWork(DBC_TABLES);
      dbcSetBusy(false);
      dbcSetStatus(problem ? "Could not stop loading: " + problem
            : "Search stopped. Incomplete results were cleared; DB Capture was not stopped.", !!problem);
   }

   /**
    * Called by the delete action so the list updates itself.
    *
    * Exposed on window deliberately: DbCaptureCommands returns a JAVASCRIPT
    * FormResult that looks this up by name, and the action's script may run in
    * a frame, so the caller walks up to find whichever window owns the page.
    */
   window.dbCaptureReloadAfterDelete = function () {
      // The capture being filtered on may be one of the ones just deleted.
      try {
         var grid = dbcTable(DBC_SESSION_TABLE);
         if (grid && dbcCaptureFilter) {
            var stillThere = false;
            grid.getStore().each(function (record) {
               if (String(record.get("captureId")) === dbcCaptureFilter) {
                  stillThere = true;
               }
            });
            if (!stillThere) {
               dbcCaptureFilter = null;
               dbcShowFilterRow();
            }
         }
      } catch (e) {
         dbcCaptureFilter = null;
         dbcShowFilterRow();
      }
      dbcRunSearch(DBC_TABLES);
   };

   /* ---- Monitoring scope ------------------------------------------------ */

   function dbcSetScopeStatus(text, isProblem) {
      dbcMessage("dbcScopeStatus", text, isProblem);
   }

   var dbcScopeLoaded = false;
   var dbcScopeLoading = false;
   var dbcScopeSaving = false;
   var dbcScopeEditing = false;
   var dbcSavedScope = null;
   var dbcScopeModel = null;

   function dbcScopeControls() {
      var busy = dbcScopeLoading || dbcScopeSaving;
      var editable = dbcScopeLoaded && dbcScopeEditing && !busy;
      var ids = ["dbcScopeExcluded", "dbcScopeIncluded",
                 "dbcIncludeScopeButton", "dbcExcludeScopeButton"];
      for (var i = 0; i < ids.length; i++) {
         var input = document.getElementById(ids[i]);
         if (input) { input.disabled = !editable; }
      }
      document.getElementById("dbcScopeFind").disabled = busy || !dbcScopeLoaded;
      document.getElementById("dbcEditScopeButton").disabled = busy || !dbcScopeLoaded || dbcScopeEditing;
      document.getElementById("dbcCancelScopeButton").disabled = !editable;
      document.getElementById("dbcReloadScopeButton").disabled = busy;
      document.getElementById("dbcReloadScopeButton").value =
            dbcScopeLoading ? "Loading..." : dbcScopeLoaded ? "Reload" : "Retry loading scope";
      document.getElementById("dbcSaveScopeButton").disabled = !editable
            || dbcScopeSignature() === dbcSavedScope.included.join(",");
      document.getElementById("dbcSaveScopeButton").value = dbcScopeSaving ? "Saving..." : "Save";
   }

   function dbcScopeSignature() {
      return dbcScopeModel ? dbcScopeModel.included.join(",") : "";
   }

   function dbcRenderScope() {
      if (!dbcScopeModel) { return; }
      var term = document.getElementById("dbcScopeFind").value.trim().toUpperCase();
      ["excluded", "included"].forEach(function (side) {
         var suffix = side === "excluded" ? "Excluded" : "Included";
         var list = document.getElementById("dbcScope" + suffix);
         list.innerHTML = "";
         var shown = 0;
         dbcScopeModel[side].forEach(function (name) {
            if (term && name.toUpperCase().indexOf(term) < 0) { return; }
            var option = document.createElement("option");
            option.value = name;
            option.textContent = name;
            option.title = dbcScopeModel.reasons[name] || "";
            list.appendChild(option);
            shown++;
         });
         document.getElementById("dbcScope" + suffix + "Count").textContent =
               "(" + shown + " / " + dbcScopeModel[side].length + ")";
      });
      var locked = document.getElementById("dbcScopeLocked");
      locked.innerHTML = "";
      var shownLocked = 0;
      dbcScopeModel.locked.forEach(function (name) {
         if (term && name.indexOf(term) < 0) { return; }
         var row = document.createElement("li");
         row.textContent = name + " - " + dbcScopeModel.reasons[name];
         locked.appendChild(row);
         shownLocked++;
      });
      document.getElementById("dbcScopeLockedCount").textContent =
            "(" + shownLocked + " / " + dbcScopeModel.locked.length + ")";
      document.getElementById("dbcScopeNotice").textContent = dbcScopeModel.notice;
   }

   function dbcScopeCopy(model) {
      return {excluded: model.excluded.slice(), included: model.included.slice(),
         locked: model.locked.slice(), reasons: model.reasons, notice: model.notice};
   }

   function dbcAcceptScope(data) {
      if (!data || !Array.isArray(data.scopeExcluded) || !Array.isArray(data.scopeIncluded)
            || !Array.isArray(data.scopeLocked) || !data.scopeReasons
            || typeof data.scopeReasons !== "object" || typeof data.scopeNotice !== "string") {
         return false;
      }
      var movable = data.scopeExcluded.concat(data.scopeIncluded);
      var names = movable.concat(data.scopeLocked);
      var seen = Object.create(null);
      for (var i = 0; i < names.length; i++) {
         if (typeof names[i] !== "string" || !names[i]
               || (i < movable.length && !/^[A-Z0-9_$#]+$/.test(names[i]))
               || seen[names[i]] || typeof data.scopeReasons[names[i]] !== "string") {
            return false;
         }
         seen[names[i]] = true;
      }
      dbcScopeModel = {
         excluded: data.scopeExcluded.slice().sort(), included: data.scopeIncluded.slice().sort(),
         locked: data.scopeLocked.slice().sort(), reasons: data.scopeReasons, notice: data.scopeNotice
      };
      dbcSavedScope = dbcScopeCopy(dbcScopeModel);
      dbcScopeLoaded = true;
      dbcScopeEditing = false;
      dbcRenderScope();
      return true;
   }

   function dbCaptureLoadScope() {
      if (dbcScopeLoading || dbcScopeSaving) { return; }
      if (dbcScopeEditing && dbcScopeSignature() !== dbcSavedScope.included.join(",")
            && !window.confirm("Discard unsaved monitoring scope changes and reload?")) { return; }
      dbcScopeLoading = true;
      dbcScopeControls();
      dbcSetScopeStatus("Loading monitoring scope...");
      dbcPostJson("op=scope", function (problem, data) {
         dbcScopeLoading = false;
         if (problem || !dbcAcceptScope(data)) {
            dbcScopeLoaded = false;
            dbcSetScopeStatus("Could not read monitoring scope: "
                  + (problem || "Incomplete settings response.")
                  + " Use Retry loading scope before making changes.", true);
         } else {
            dbcSetScopeStatus("Saved monitoring scope loaded. Changes apply to the next capture.");
         }
         dbcScopeControls();
      });
   }

   function dbcEditScope() {
      if (!dbcScopeLoaded || dbcScopeLoading || dbcScopeSaving) { return; }
      dbcScopeEditing = true;
      dbcScopeControls();
      dbcSetScopeStatus("Editing. Select tables and use the arrows; Save applies to the next capture.");
   }

   function dbcCancelScope() {
      if (!dbcScopeEditing || dbcScopeSaving || dbcScopeLoading) { return; }
      dbcScopeModel = dbcScopeCopy(dbcSavedScope);
      dbcScopeEditing = false;
      dbcRenderScope();
      dbcScopeControls();
      dbcSetScopeStatus("Changes cancelled. The saved monitoring scope is unchanged.");
   }

   function dbcMoveScope(include) {
      if (!dbcScopeEditing || !dbcScopeLoaded || dbcScopeSaving || dbcScopeLoading) { return; }
      var source = include ? "excluded" : "included";
      var target = include ? "included" : "excluded";
      var list = document.getElementById(include ? "dbcScopeExcluded" : "dbcScopeIncluded");
      var selected = [];
      for (var i = 0; i < list.options.length; i++) {
         if (list.options[i].selected) { selected.push(list.options[i].value); }
      }
      if (!selected.length) {
         dbcSetScopeStatus("Select at least one table to move.");
         return;
      }
      dbcScopeModel[source] = dbcScopeModel[source].filter(function (name) {
         return selected.indexOf(name) < 0;
      });
      dbcScopeModel[target] = dbcScopeModel[target].concat(selected).sort();
      dbcRenderScope();
      dbCaptureScopeChanged();
   }

   function dbCaptureSaveScope() {
      if (dbcScopeLoading || dbcScopeSaving) { return; }
      if (!dbcScopeLoaded || !dbcScopeEditing) {
         dbcSetScopeStatus("Load the saved scope and click Edit before saving.", true);
         return;
      }
      if (dbcScopeSignature() === dbcSavedScope.included.join(",")) {
         dbcSetScopeStatus("No scope changes to save.");
         return;
      }
      dbcScopeSaving = true;
      dbcScopeControls();
      dbcSetScopeStatus("Saving...");
      var body = "op=settings"
         + "&includedTables=" + encodeURIComponent(dbcScopeModel.included.join(","));

      dbcPostJson(body, function (problem, data) {
         dbcScopeSaving = false;
         if (problem) {
            dbcSetScopeStatus(problem + " Your draft has been retained.", true);
         } else if (!dbcAcceptScope(data)) {
            dbcScopeLoaded = false;
            dbcSetScopeStatus("The save returned without verifiable settings."
                  + " Retry loading scope before another save.", true);
         } else {
            dbcSetScopeStatus((data.message || "Monitoring scope saved.")
                  + " Applies to the next capture.");
         }
         dbcScopeControls();
      });
   }

   function dbCaptureScopeChanged() {
      if (!dbcScopeLoaded || !dbcScopeEditing || dbcScopeLoading || dbcScopeSaving) { return; }
      dbcScopeControls();
      dbcSetScopeStatus(dbcScopeSignature() === dbcSavedScope.included.join(",")
            ? "Scope matches the saved settings. Applies to the next capture."
            : "Unsaved changes. Save scope before starting the next capture.");
   }

   dbCaptureLoadScope();

   /**
    * Empty the keyword box and the result tables.
    *
    * Clear does not search. It used to call dbCaptureSearch() when it was
    * done, which is what made the two buttons look swapped: Clear was the one
    * that visibly went and fetched something, while Search - always coming
    * back empty - looked like it had merely reset the page. Resetting the
    * criteria and waiting to be asked again is also what the Reset button on
    * Workflow Process Administration does.
    */
   function dbCaptureClear() {
      dbcSearchToken++;   // a search still in flight stops reporting
      var problem = dbcCancelSearchWork(DBC_TABLES);
      dbcSetBusy(false);
      if (problem) {
         dbcSetStatus("Could not clear the pending search: " + problem, true);
         return;
      }

      var el = document.getElementById("dbcKeyword");
      if (el) {
         el.value = "";
         el.focus();
      }

      dbcClearTables(DBC_TABLES);
      dbcAppliedCriteria = null;
      dbcCaptureFilter = null;
      dbcShowFilterRow();
      dbcSetStatus("Cleared. Press Search to look again.");
   }

   /*
    * Wire every control from here, in script scope.
    *
    * Not with inline onclick attributes. An inline handler is compiled with
    * the element, its owner form and the document in its scope chain, and
    * begin.jspf wraps this whole page in <form name="mainform">. Any element
    * id that matches a function name therefore shadows the function: the
    * Search button's onclick="dbCaptureSearch()" resolved to the
    * <fieldset id="dbCaptureSearch"> that s:searchFieldSet renders and threw
    * "is not a function", so the button did nothing and only the console knew.
    * Clear had no matching id and worked - which is what made the pair look
    * swapped. Attaching listeners here cannot be shadowed by any markup.
    *
    * The fieldset ids no longer collide either; this is the belt as well as
    * the braces, because the next id added to this page should not be able to
    * break a button again.
    */
   (function () {
      function onClick(id, handler) {
         var el = document.getElementById(id);
         if (!el) {
            return false;
         }
         if (el.addEventListener) {
            el.addEventListener("click", handler, false);
         } else {
            el.attachEvent("onclick", handler);
         }
         return true;
      }

      var missing = [];
      var pageControl = document.getElementById("dbcSearchButton");
      dbcInstallDocumentContextSuppression();
      if (!onClick("dbcSearchButton", dbCaptureSearch))    { missing.push("Search"); }
      if (!onClick("dbcStopButton", dbCaptureStopSearch))  { missing.push("Stop"); }
      if (!onClick("dbcClearButton", dbCaptureClear))      { missing.push("Clear"); }
      if (!onClick("dbcSaveScopeButton", dbCaptureSaveScope)) {
         missing.push("Save scope");
      }
      onClick("dbcEditScopeButton", dbcEditScope);
      onClick("dbcCancelScopeButton", dbcCancelScope);
      onClick("dbcReloadScopeButton", dbCaptureLoadScope);
      onClick("dbcIncludeScopeButton", function () { dbcMoveScope(true); });
      onClick("dbcExcludeScopeButton", function () { dbcMoveScope(false); });
      document.getElementById("dbcScopeFind").addEventListener("input", dbcRenderScope, false);
      onClick("dbcAllCapturesButton", function () {
         dbcSetCaptureFilter(null);
      });

      // Unfinished captures change both tables, so this one re-runs the
      // search rather than reloading the changes alone.
      onClick("dbcShowUnfinished", function () {
         dbCaptureSearch();
      });

      dbcReportFilter();
      if (missing.length > 0) {
         dbcSetStatus("These buttons are not connected: " + missing.join(", ")
                      + " - reload the page.");
      }

      /*
       * Selecting a capture filters the changes below it, and selects the row
       * for the toolbar.
       *
       * Those were two different ideas of "selected" and only one of them was
       * happening. Clicking a row set the page's own capture filter and the
       * row highlighted, so it looked selected - but the framework's checkbox
       * stayed clear, no oid was sent with the action, and Edit Description
       * opened onto "Select a capture session first." on a row the user had
       * just clicked. Both now mean the same thing.
       *
       * The grids are built asynchronously by the table framework, so poll
       * until the session grid exists and attach once. The GridPanel object
       * survives its own data reloads, so this listener does not need
       * reattaching after every search.
       */
      var attempts = 0;
      var sessionAttached = false;
      var changeAttached = false;
      var attach = setInterval(function () {
         if (document.getElementById("dbcSearchButton") !== pageControl) {
            clearInterval(attach);
            return;
         }
         var sessionGrid = dbcTable(DBC_SESSION_TABLE);
         var sessionEl = sessionGrid && sessionGrid.getEl ? sessionGrid.getEl() : null;
         if (sessionGrid && sessionGrid.on && sessionEl && sessionEl.dom && !sessionAttached) {
            sessionGrid.on("rowclick", dbcSessionRowClick);
            sessionGrid.on("celldblclick", dbcEditDescriptionCell);
            var view = sessionGrid.getView();
            view.on("refresh", function () { dbcDescribeEditControls(sessionGrid); });
            dbcDescribeEditControls(sessionGrid);
            dbcInstallCapturedContextMenu(sessionGrid, dbcShowSessionContextMenu);
            sessionAttached = true;
         }
         var changeGrid = dbcTable(DBC_CHANGE_TABLE);
         var changeEl = changeGrid && changeGrid.getEl ? changeGrid.getEl() : null;
         if (changeGrid && changeGrid.on && changeEl && changeEl.dom && !changeAttached) {
            changeGrid.getView().on("refresh", function () { dbcDescribeCsvControl(changeGrid); });
            dbcDescribeCsvControl(changeGrid);
            dbcInstallCapturedContextMenu(changeGrid, function (g, rowIndex, xy) {
               dbcShowChangeContextMenu(g, rowIndex, {
                  stopEvent: function () {},
                  getXY: function () { return xy; }
               });
            });
            changeAttached = true;
         }
         if (sessionAttached && changeAttached) {
            clearInterval(attach);
            return;
         }
         if (++attempts > 60) {
            clearInterval(attach);
            dbcSetStatus("Could not connect all result-table actions. Reload this page.", true);
         }
      }, 500);

      function onEnter(id, handler) {
         var input = document.getElementById(id);
         if (!input) { return; }
         input.onkeydown = function (e) {
            var ev = e || window.event;
            if (ev.keyCode === 13) {
               if (ev.preventDefault) { ev.preventDefault(); }
               handler();
               return false;
            }
            return true;
         };
      }
      onEnter("dbcKeyword", dbCaptureSearch);
      onEnter("dbcScopeFind", dbcRenderScope);
      dbcAutoSearchCompleted(DBC_COMPLETED_CAPTURE);
   })();
</script>

<%@ include file="/netmarkets/jsp/util/end.jspf"%>
