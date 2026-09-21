<%--
  Edit the description of one capture session.

  A small popup rather than an in-cell editor. Windchill's own inline table
  editing needs the table in EDIT mode with a form processor behind it, which
  is a much larger piece of machinery than one text field justifies; this is
  the standard popup shape used across the product and it works predictably.
--%><%@ taglib uri="http://www.ptc.com/windchill/taglib/components" prefix="jca"%>
<%@ taglib uri="http://www.ptc.com/windchill/taglib/wrappers" prefix="w"%>
<%@ page import="com.ptc.dbcapture.DbCaptureAuthorization"%>
<%@ page import="com.ptc.dbcapture.DbCaptureHelper"%>
<%@ page import="com.ptc.dbcapture.DbCaptureSession"%>
<%@ page import="com.ptc.netmarkets.model.NmOid"%>
<%--
  wt.util.HTMLEncoder has no encode(String). The methods are
  encodeForHTMLContent (text), encodeForHTMLAttribute (an attribute value) and
  encodeForJavascript (a JS string literal) - pick the one matching where the
  value lands, and note that the wrong name fails the whole JSP at compile
  time, not just the one expression.
--%><%@ page import="wt.util.HTMLEncoder"%>

<%@ include file="/netmarkets/jsp/util/beginPopup.jspf"%>

<%
   String captureId = "";
   long sessionOid = 0L;
   String description = "";
   String problem = null;
   // Not named "session": a JSP already has an implicit HttpSession by that
   // name, and shadowing it fails the whole page at compile time.
   DbCaptureSession capture = null;
   try {
      DbCaptureAuthorization.requireAdministrator();
      // Toolbar popups carry the selected NmContext in soid, not the page's oid.
      java.util.List<?> selected = commandBean.getSelectedOidForPopup();
      if (selected != null && !selected.isEmpty()) {
         if (selected.size() != 1) {
            throw new wt.util.WTException("Select exactly one capture session to edit.");
         }
         NmOid selectedOid = NmCommandBean.getOidFromObject(selected.get(0));
         Object target = selectedOid == null ? null : selectedOid.getRefObject();
         if (!(target instanceof DbCaptureSession)) {
            throw new wt.util.WTException("The selected object is not a capture session.");
         }
         capture = (DbCaptureSession) target;
      }
      if (capture == null) {
         String requested = request.getParameter("captureId");
         if (requested != null && !requested.trim().isEmpty()) {
            capture = DbCaptureHelper.findSession(requested.trim());
         }
      }
      if (capture == null) {
         String oid = request.getParameter("oid");
         if (oid != null && !oid.trim().isEmpty()) {
            Object target = new NmOid(oid).getRefObject();
            if (target instanceof DbCaptureSession) {
               capture = (DbCaptureSession) target;
            }
         }
      }
      if (capture != null) {
         captureId = capture.getCaptureId() == null ? "" : capture.getCaptureId();
         sessionOid = wt.fc.PersistenceHelper.getObjectIdentifier(capture).getId();
         description =
            capture.getDescription() == null ? "" : capture.getDescription();
      } else {
         problem = "No capture session came through with this request. Tick"
                 + " the checkbox at the left of a row in Capture Sessions,"
                 + " then press Edit Description.";
      }
   } catch (Exception e) {
      problem = e.getMessage() == null ? e.toString() : e.getMessage();
      org.apache.logging.log4j.LogManager
         .getLogger("com.ptc.dbcapture.editDescription")
         .error("Could not resolve the capture selected for description editing.", e);
   }
%>

<div style="padding: 14px; min-width: 460px;">
<% if (problem != null) { %>
   <div style="color: #a00;"><%=HTMLEncoder.encodeForHTMLContent(problem)%></div>
<% } else { %>
   <div style="margin-bottom: 8px;">
      <b>Capture ID:</b> <%=HTMLEncoder.encodeForHTMLContent(captureId)%>
   </div>
   <div style="margin-bottom: 4px;"><label for="dbcDescValue">Description (maximum 400 characters)</label></div>
   <input type="text" id="dbcDescValue" aria-label="Description" style="width: 440px;"
          maxlength="400" value="<%=HTMLEncoder.encodeForHTMLAttribute(description)%>"/>
<% } %>
   <div style="margin-top: 14px; text-align: right;">
      <input type="button" id="dbcDescCancel" value="Cancel"/>
      <% if (problem == null) { %>
         <input type="button" id="dbcDescSave" value="Save"/>
      <% } %>
   </div>
   <div id="dbcDescStatus" role="status" aria-live="polite"
        style="margin-top: 8px; color: #555;"></div>
</div>

<script type="text/javascript">
   var DBC_CAPTURE_ID = "<%=HTMLEncoder.encodeForJavascript(captureId)%>";
   var DBC_SESSION_OID = "<%=sessionOid%>";
   var DBC_ENDPOINT = "netmarkets/jsp/dbcapture/dbCaptureState.jsp";
   var dbcSaving = false;
   var dbcSaved = false;

   function dbcCancel() {
      if (dbcSaving) {
         document.getElementById("dbcDescStatus").textContent =
               "The save is still in progress. Wait for its result before closing.";
         return;
      }
      if (window.close) { window.close(); }
   }

   function dbcSave() {
      if (dbcSaving || dbcSaved) { return; }
      var value = document.getElementById("dbcDescValue").value;
      var status = document.getElementById("dbcDescStatus");
      var save = document.getElementById("dbcDescSave");
      var cancel = document.getElementById("dbcDescCancel");
      if (value.length > 400) {
         status.textContent = "Description must be 400 characters or fewer. Nothing was saved.";
         return;
      }
      dbcSaving = true;
      save.disabled = true;
      cancel.disabled = true;
      status.textContent = "Saving...";

      function failure(message) {
         dbcSaving = false;
         save.disabled = false;
         cancel.disabled = false;
         status.textContent = message;
      }

      var req = new XMLHttpRequest();
      req.open("POST", DBC_ENDPOINT, true);
      req.timeout = 30000;
      req.setRequestHeader("Content-Type",
                           "application/x-www-form-urlencoded; charset=UTF-8");
      req.onreadystatechange = function () {
         if (req.readyState !== 4) { return; }
         var data;
         try {
            data = JSON.parse(req.responseText);
         } catch (e) {
            failure("Could not save: unreadable server response (HTTP " + req.status + ").");
            return;
         }
         if (req.status >= 200 && req.status < 300 && data && data.ok === true) {
            dbcSaving = false;
            dbcSaved = true;
            cancel.disabled = false;
            cancel.value = "Close";
            document.getElementById("dbcDescValue").disabled = true;
            status.textContent = "Description saved.";
            try {
               var opener = window.opener || window.parent;
               if (opener && typeof opener.dbCaptureSearch === "function"
                     && opener.document && opener.document.getElementById("dbcSearchButton")) {
                  opener.dbCaptureSearch();
               } else {
                  status.textContent += " Reload Capture Sessions to see the change.";
                  return;
               }
            } catch (e) {
               status.textContent += " Could not refresh Capture Sessions: " + e;
               return;
            }
            dbcCancel();
         } else {
            failure("Could not save (HTTP " + req.status + "): "
                  + (data && data.message ? data.message : "The server rejected the change."));
         }
      };
      req.onerror = function () {
         failure("Could not reach DB Capture. Reload the description before retrying.");
      };
      req.ontimeout = function () {
         failure("Saving timed out. Reload the description before retrying; it may have been saved.");
      };
      req.send("op=describe&sessionOid=" + encodeURIComponent(DBC_SESSION_OID)
             + "&captureId=" + encodeURIComponent(DBC_CAPTURE_ID)
             + "&description=" + encodeURIComponent(value));
   }

   (function () {
      function onClick(id, handler) {
         var el = document.getElementById(id);
         if (el && el.addEventListener) {
            el.addEventListener("click", handler, false);
         } else if (el) {
            el.attachEvent("onclick", handler);
         }
      }
      onClick("dbcDescCancel", dbcCancel);
      onClick("dbcDescSave", dbcSave);
      var input = document.getElementById("dbcDescValue");
      if (input) {
         input.addEventListener("keydown", function (event) {
            if (event.keyCode === 13 || event.keyCode === 27) {
               event.preventDefault();
               if (event.keyCode === 13) { dbcSave(); }
               else { dbcCancel(); }
            }
         }, false);
         input.focus();
         input.select();
      }
   })();
</script>

<%@ include file="/netmarkets/jsp/util/end.jspf"%>
