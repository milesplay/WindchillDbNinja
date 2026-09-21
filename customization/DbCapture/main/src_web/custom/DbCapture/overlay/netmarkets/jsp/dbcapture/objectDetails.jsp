<%@ page contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true"
%><%@ page import="com.ptc.dbcapture.DbCaptureObjectReader"
%><%@ page import="wt.util.HTMLEncoder"
%><%
   response.setHeader("Cache-Control", "no-store");
   response.setHeader("X-Content-Type-Options", "nosniff");
   DbCaptureObjectReader.Snapshot snapshot = null;
   String problem = null;
   try {
      com.ptc.dbcapture.DbCaptureAuthorization.requireAdministrator();
   } catch (wt.util.WTException e) {
      problem = e.getMessage() == null ? e.toString() : e.getMessage();
      response.setStatus(403);
      org.apache.logging.log4j.LogManager.getLogger("com.ptc.dbcapture.objectDetails")
            .warn("Captured-object read denied.", e);
   }
   if (problem == null) {
      try {
         snapshot = com.ptc.dbcapture.DbCaptureHelper.service.inspectObject(request.getParameter("entry"));
      } catch (wt.util.WTException | IllegalArgumentException e) {
         problem = e.getMessage() == null ? e.toString() : e.getMessage();
         response.setStatus(400);
         org.apache.logging.log4j.LogManager.getLogger("com.ptc.dbcapture.objectDetails")
               .error("Captured-object read failed.", e);
      }
   }
%><!DOCTYPE html>
<html lang="en">
<head>
   <meta charset="UTF-8"/>
   <meta name="viewport" content="width=device-width, initial-scale=1"/>
   <title>DB Capture - Current Object Data</title>
   <style>
      body { font: 14px Arial, sans-serif; margin: 24px; color: #222; }
      table { border-collapse: collapse; width: 100%; margin-bottom: 24px; }
      th, td { border: 1px solid #ccc; padding: 7px 10px; text-align: left; vertical-align: top; }
      th { background: #eef1f4; }
      td { overflow-wrap: anywhere; font-family: monospace; }
      .current-value, .changed-value { white-space: pre-wrap; }
      .notice { padding: 10px; background: #fff4cf; }
      .error { color: #a00; white-space: pre-wrap; }
      .object-created { color: #187a30; }
      .object-updated { color: #b00020; }
      .object-deleted { color: #000; }
      .object-deleted h1, .object-deleted h2 { text-decoration: line-through; }
      .captured-change { margin-top: 8px; padding-top: 7px; border-top: 1px dashed #ccc; }
      .captured-values { margin: 6px 0; }
      .captured-values dt { font-weight: bold; }
      .captured-values dd { margin: 0 0 6px; }
      .changed-value { color: #b00020; }
   </style>
</head>
<body>
<% if (problem != null) { %>
   <h1>Current Object Data</h1>
   <div class="error" role="alert"><%=HTMLEncoder.encodeForHTMLContent(problem)%></div>
<% } else { %>
   <header id="objectHeading" class="<%=snapshot.operationClass()%>">
      <h1>Current Object Data</h1>
      <h2><%=HTMLEncoder.encodeForHTMLContent(snapshot.reference())%></h2>
   </header>
   <p>Captured operation: <strong><%=HTMLEncoder.encodeForHTMLContent(snapshot.operation())%></strong></p>
   <p>Current committed data at page read time, not the historical capture snapshot.
      Filter: IDA2A2 = <%=snapshot.rowId()%>.</p>
   <p>Read at: <%=HTMLEncoder.encodeForHTMLContent(wt.util.WTStandardDateFormat.format(snapshot.readAt()))%>.
      Returned: <%=snapshot.rows().size()%>.</p>
   <% if (snapshot.truncated()) { %>
      <p class="notice">More matching rows exist. Only the first 100 are displayed.</p>
   <% } %>
   <% if (snapshot.rows().isEmpty()) { %>
      <p class="notice">No current row matches this captured ID. It may have been physically deleted.
         This does not remove its saved capture details.</p>
      <% if (!snapshot.changes().isEmpty()) { %>
         <h3>Captured changes without a current row</h3>
         <p>These are saved before/after values, not current database values.</p>
         <table>
            <thead><tr><th scope="col">Column</th><th scope="col">Captured values (not current)</th></tr></thead>
            <tbody>
            <% for (DbCaptureObjectReader.ChangedValue change : snapshot.changes()) { %>
               <tr><th scope="row"><%=HTMLEncoder.encodeForHTMLContent(change.column())%></th>
                  <td>
                     <dl class="captured-values">
                        <dt>Before (captured):</dt>
                        <dd class="changed-value"><%=HTMLEncoder.encodeForHTMLContent(DbCaptureObjectReader.displayValue(change.before()))%></dd>
                        <dt>After (captured):</dt>
                        <dd class="changed-value"><%=HTMLEncoder.encodeForHTMLContent(DbCaptureObjectReader.displayValue(change.after()))%></dd>
                     </dl>
                     <% if (change.truncated()) { %><small>Captured value preview is truncated.</small><% } %>
                  </td></tr>
            <% } %>
            </tbody>
         </table>
      <% } %>
   <% } %>
   <% for (int row = 0; row < snapshot.rows().size(); row++) { %>
      <h3>Entry <%=row + 1%></h3>
      <table>
         <thead><tr><th scope="col">Column</th><th scope="col">Current value</th></tr></thead>
         <tbody>
         <% for (int column = 0; column < snapshot.columns().size(); column++) {
               String value = snapshot.rows().get(row).get(column);
               DbCaptureObjectReader.ChangedValue change = snapshot.changeFor(snapshot.columns().get(column));
         %>
            <tr><th scope="row"><%=HTMLEncoder.encodeForHTMLContent(snapshot.columns().get(column))%></th>
                <td><div class="current-value"><%=HTMLEncoder.encodeForHTMLContent(DbCaptureObjectReader.displayValue(value))%></div>
                   <% if (change != null) { %>
                      <div class="captured-change">Changed value (captured):
                         <dl class="captured-values">
                            <dt>Before (captured):</dt>
                            <dd class="changed-value"><%=HTMLEncoder.encodeForHTMLContent(DbCaptureObjectReader.displayValue(change.before()))%></dd>
                            <dt>After (captured):</dt>
                            <dd class="changed-value"><%=HTMLEncoder.encodeForHTMLContent(DbCaptureObjectReader.displayValue(change.after()))%></dd>
                         </dl>
                         <% if (change.truncated()) { %><small>Captured value preview is truncated.</small><% } %>
                      </div>
                   <% } %>
                </td></tr>
         <% } %>
         </tbody>
      </table>
   <% } %>
<% } %>
</body>
</html>
