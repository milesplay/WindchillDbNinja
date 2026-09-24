<%@ page contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true"
%><%@ page import="com.custom.dbcapture.DbCaptureDiagnostics"
%><%@ page import="com.custom.dbcapture.diagnostics.SqlEvidencePresentation"
%><%@ page import="wt.util.HTMLEncoder"
%><%!
   private static String html(String value) {
      return HTMLEncoder.encodeForHTMLContent(value == null ? "" : value);
   }

   private static void tree(jakarta.servlet.jsp.JspWriter out,
                            java.util.List<SqlEvidencePresentation.Node> nodes) throws java.io.IOException {
      out.write("<ul>");
      for (SqlEvidencePresentation.Node node : nodes) {
         out.write("<li><details class=\"call-node\" open><summary><code>");
         out.write(html(node.frame()));
         out.write("</code></summary>");
         if (!node.children().isEmpty()) tree(out, node.children());
         if (!node.statements().isEmpty()) {
            out.write("<ul class=\"sql-leaves\">");
            for (SqlEvidencePresentation.StatementRef statement : node.statements()) {
               out.write("<li><a class=\"sql-leaf\" href=\"#statement-" + statement.sequence() + "\">");
               out.write(html(statement.label()));
               out.write("</a></li>");
            }
            out.write("</ul>");
         }
         out.write("</details></li>");
      }
      out.write("</ul>");
   }
%><%
   response.setHeader("Cache-Control", "no-store");
   response.setHeader("X-Content-Type-Options", "nosniff");
   String kind = request.getParameter("view");
   String problem = null;
   DbCaptureDiagnostics.Report report = null;
   try {
      com.custom.dbcapture.DbCaptureAuthorization.requireAdministrator();
   } catch (wt.util.WTException e) {
      problem = e.getLocalizedMessage();
      response.setStatus(403);
   }
   if (problem == null) {
      try {
         if (kind != null && !"sql".equals(kind) && !"stack".equals(kind)) {
            throw new IllegalArgumentException("Unknown capture diagnostic view.");
         }
         report = com.custom.dbcapture.DbCaptureHelper.service.readDiagnostics(request.getParameter("capture"));
      } catch (wt.util.WTException | IllegalArgumentException e) {
         problem = e.getMessage() == null ? e.toString() : e.getMessage();
         response.setStatus(400);
         org.apache.logging.log4j.LogManager.getLogger("com.custom.dbcapture.captureDiagnostics")
               .error("Capture diagnostic read failed.", e);
      }
   }
%><!DOCTYPE html>
<html lang="en">
<head>
   <meta charset="UTF-8"/>
   <meta name="viewport" content="width=device-width, initial-scale=1"/>
   <title>DB Capture - AJP SQL / Stack Trace</title>
   <style>
      * { box-sizing: border-box; }
      body { font: 14px Arial, sans-serif; margin: 24px; color: #222; background: #fff; }
      h1 { margin-bottom: 8px; } h2 { font-size: 18px; } h3 { font-size: 15px; }
      a { color: #075aa4; } summary { cursor: pointer; padding: 6px 0; }
      code, pre { font: 12px/1.5 Consolas, monospace; }
      pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 25em; overflow: auto;
            padding: 12px; border: 1px solid #d4dce4; background: #f6f8fa; }
      .notice, .warning-summary { padding: 10px 12px; background: #fff4cf; }
      .error { color: #a00; white-space: pre-wrap; }
      .muted { color: #4f5964; } .badge { display: inline-block; padding: 5px 10px; background: #eef2f5; }
      .health { font-weight: bold; margin: 0 12px 8px 0; }
      .lamp { display: inline-block; width: 13px; height: 13px; border-radius: 50%;
              border: 1px solid #555; margin-right: 6px; vertical-align: -1px; }
      .green { background: #238636; } .yellow { background: #f5c518; } .red { background: #cf222e; }
      .request { border-top: 2px solid #cbd5df; margin-top: 22px; padding-top: 6px; }
      .request > summary { font-size: 18px; font-weight: bold; overflow-wrap: anywhere; }
      .request-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr); gap: 20px; }
      .request-layout > div { min-width: 0; }
      .call-tree > summary, .sql-section > summary { font-size: 15px; font-weight: bold; }
      .scope-layout { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
      .scope-layout > details { min-width: 0; }
      .tree-panel, .statements { max-height: 72vh; overflow: auto; padding: 6px; border: 1px solid #d4dce4; }
      .tree-panel ul { list-style: none; margin: 0 0 0 8px; padding-left: 14px; border-left: 1px solid #cbd5df; }
      .tree-panel summary { white-space: nowrap; }
      .tree-panel > ul { margin-left: 0; }
      .sql-leaves li { padding: 5px 0; white-space: nowrap; }
      .sql-leaf { font-weight: bold; }
      .statement-card { margin-bottom: 10px; padding: 8px 12px; border: 1px solid #cbd5df; }
      .statement-card.selected { outline: 2px solid #075aa4; outline-offset: -2px; }
      .statement-card > summary { font-weight: bold; }
      .operation { padding: 2px 5px; margin: 0 5px; border-radius: 3px; background: #eef2f5; }
      .operation.INSERT { color: #116329; background: #dafbe1; }
      .operation.UPDATE { color: #a40e26; background: #ffebe9; }
      .operation.DELETE { color: #222; background: #eaeef2; }
      .raw-stack { white-space: pre; overflow-wrap: normal; }
      .tools { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0; }
      button { padding: 5px 10px; cursor: pointer; }
      button:disabled { cursor: wait; }
      .sql-feedback, .tree-feedback { display: inline-block; margin: 5px 0; }
      dl { display: grid; grid-template-columns: 165px minmax(150px, 1fr); gap: 5px 12px; }
      dd { margin: 0; overflow-wrap: anywhere; }
      .technical { margin: 12px 0; padding: 8px 12px; border: 1px solid #d4dce4; }
      @media (max-width: 950px) { .request-layout, .scope-layout { grid-template-columns: minmax(0, 1fr); } }
   </style>
   <script defer src="<%=HTMLEncoder.encodeForHTMLAttribute(request.getContextPath()
         + "/custom/DbCapture/dbCaptureDiagnostics-v20260920.js")%>"></script>
</head>
<body>
<h1>AJP SQL / Stack Trace</h1>
<% if (problem != null) { %>
   <div class="error" role="alert"><%=html(problem)%></div>
<% } else { %>
   <h2><%=html(report.captureId())%></h2>
   <div class="health"><span class="lamp <%=report.health().tone().name().toLowerCase(java.util.Locale.ROOT)%>"
         role="img" aria-label="<%=HTMLEncoder.encodeForHTMLAttribute(report.health().label())%>"></span>
      <%=html(report.health().label())%>
      <span class="badge"><%=report.recorded()%> SQL statements / <%=report.requests().size()%> AJP requests</span>
      <span class="badge">SQL evidence: <%=html(report.state())%></span>
   </div>
   <% if (!report.health().summary().isEmpty()) { %>
      <pre class="warning-summary"><%=html(report.health().summary())%></pre>
   <% } %>
   <p class="muted">Only authenticated AJP requests matched to their native Servlet request and MethodContext
      are shown. SQL and stacks are observed synchronously before JDBC execution, not inferred from row changes.
      CREATE means INSERT. Placeholders remain unchanged; these records do not prove execution or commit.</p>
   <details class="technical" id="capture-diagnostics">
      <summary>Full capture diagnostics, warnings and table scope</summary>
      <dl>
         <dt>Capture status / mode</dt><dd><%=html(report.captureStatus())%> / <%=html(report.mode())%></dd>
         <dt>Evidence state</dt><dd><%=html(report.state())%></dd>
         <dt>Detail</dt><dd><%=html(report.reason())%></dd>
         <dt>MethodServer node</dt><dd><%=html(report.node())%></dd>
         <dt>Recorded statements</dt><dd><%=report.recorded()%></dd>
         <dt>Observed / filtered</dt><dd><%=report.observed()%> / <%=report.filtered()%></dd>
         <dt>Rejected contexts</dt><dd><%=report.rejected()%> (includes intentionally excluded requests)</dd>
         <dt>Withheld statements</dt><dd>Failed requests: <%=report.failed()%>;
            unfinished requests: <%=report.unfinished()%>; over limit: <%=report.discarded()%></dd>
      </dl>
      <h3>Saved warnings</h3><pre id="full-warnings"><%=html(report.warnings() == null ? "None recorded." : report.warnings())%></pre>
      <% if (report.error() != null && !report.error().isBlank()) { %>
         <h3>Saved error</h3><pre class="error"><%=html(report.error())%></pre>
      <% } %>
      <p>Scope is frozen at Start: monitored physical tables with IDA2A2, excluding diagnostic tables.
         Recorded scope lists eligible tables, not just tables with an observed statement;
         older captures can have a narrower scope. Not Recorded is the saved Start-time catalog
         minus that recorded scope, not the current Monitoring Scope.
         SELECT, background work, DB Capture requests, unsupported forms and paths without native SQL logging
         are not covered. Failed/unfinished requests are withheld. Capture is local to the Start node.
         Limits: 10 minutes, 2,000 statements, 8 MiB; stacks may be explicitly truncated.</p>
      <div class="scope-layout">
         <details><summary>Recorded table scope (<%=report.tables().size()%>)</summary>
            <pre><%=html(report.tables().isEmpty() ? "No recorded table scope is saved."
                  : String.join(", ", report.tables()))%></pre>
         </details>
         <details><summary>Not Recorded table scope (<%=report.notRecordedScopeAvailable()
               ? Integer.toString(report.notRecordedTables().size()) : "unavailable"%>)</summary>
            <% if (report.notRecordedScopeAvailable()) { %>
               <pre><%=html(report.notRecordedTables().isEmpty() ? "None in the saved Start-time catalog."
                     : String.join(", ", report.notRecordedTables()))%></pre>
            <% } else { %>
               <p class="notice">Not Recorded scope is unavailable: this capture has no saved
                  Start-time table catalog. Legacy or unavailable evidence is never reconstructed
                  from current settings.</p>
            <% } %>
         </details>
      </div>
   </details>
   <% if (report.recorded() == 0) { %>
      <div class="notice" role="status">
         <strong><%="UNAVAILABLE".equals(report.state()) ? "No saved native SQL evidence for this capture."
               : "No eligible completed-request SQL is recorded at this checkpoint."%></strong>
         <p><%=html(report.reason())%></p>
         <p>Historical SQL/stacks cannot be reconstructed from database differences.
            For new evidence: choose Ninja Trick in Quick Links, perform the operation in this Windchill UI,
            then choose Ninja Stealth within 10 minutes on the same MethodServer.
            Review Monitoring Scope and the counters above.</p>
      </div>
   <% } %>
   <p id="diagnostic-feedback" role="status" aria-live="polite"></p>
   <% if (!report.requests().isEmpty()) { %>
      <p class="muted">Expand or collapse a request to open or close all of its SQL and call-tree details.
         Individual SQL and call-tree sections can also be toggled independently.</p>
   <% } %>
   <% for (SqlEvidencePresentation.Request group : report.requests()) { %>
      <details class="request" open>
         <summary><%=html(group.thread())%> / <%=html(group.user())%> /
            Request <%=html(group.id())%> / <%=group.statements().size()%> SQL</summary>
         <details><summary>Request identity: <%=html(group.uri())%></summary>
            <dl><dt>Servlet request</dt><dd><%=html(group.id())%></dd>
               <dt>AJP thread</dt><dd><%=html(group.thread())%></dd>
               <dt>Authenticated user</dt><dd><%=html(group.user())%></dd></dl>
         </details>
         <div class="request-layout">
            <div>
               <details class="call-tree" open>
               <summary>Issuing call tree</summary>
               <p class="muted">Caller to SQL. Equal paths are grouped within one MethodContext only.
                  Framework/logging frames are hidden here, not removed from the full captured stack.
                  Select a SQL leaf to open its exact statement.</p>
               <div class="tools">
                  <button type="button" data-tree-action="expand">Expand tree</button>
                  <button type="button" data-tree-action="collapse">Collapse tree</button>
                  <button type="button" class="copy-tree">Copy Call Tree</button>
                  <span class="tree-feedback" role="status" aria-live="polite"></span>
               </div>
               <pre class="call-tree-text" hidden><%=html(SqlEvidencePresentation.callTreeText(group))%></pre>
               <div class="tree-panel">
                  <% for (SqlEvidencePresentation.Context context : group.contexts()) { %>
                     <details class="method-context" open>
                        <summary>MethodContext <%=html(context.id())%></summary>
                        <% if (!context.target().isEmpty()) { %><p><%=html(context.target())%></p><% } %>
                        <% tree(out, context.roots()); %>
                     </details>
                  <% } %>
               </div>
               </details>
            </div>
            <div>
               <details class="sql-section" open>
               <summary>SQL in observation order</summary>
               <p class="muted">The sequence is the native observation order, not a commit order.
                  Bind text is shown only when present in the native message.</p>
               <div class="statements">
                  <% for (SqlEvidencePresentation.Statement statement : group.statements()) { %>
                     <details class="statement-card" id="statement-<%=statement.sequence()%>" open>
                        <summary>#<%=statement.sequence()%>
                           <span class="operation <%=html(statement.operation())%>"><%=html(statement.operationLabel())%></span>
                           <%=html(statement.table())%>
                           <span class="muted"><%=html(wt.util.WTStandardDateFormat.format(
                                 new java.util.Date(statement.timestampMillis())))%></span></summary>
                        <button type="button" class="copy-sql">Copy SQL</button>
                        <span class="sql-feedback" role="status" aria-live="polite"></span>
                        <pre class="sql-text"><%=html(statement.sql())%></pre>
                        <% if (!statement.binds().isEmpty()) { %>
                           <h3>Native bind text (not reconstructed)</h3><pre><%=html(statement.binds())%></pre>
                        <% } else { %><p class="muted">Bind values were not recorded by native logging.</p><% } %>
                        <% if (statement.stackTruncated()) { %>
                           <p class="notice">The recorded stack reached its frame limit; the outer caller path is incomplete.</p>
                        <% } %>
                        <details><summary>Full captured stack (<%=statement.hiddenFrames()%> framework/logging frames hidden in the tree)</summary>
                           <pre class="raw-stack"><%=html(statement.rawStack())%></pre></details>
                        <details><summary>Original native message (unchanged)</summary>
                           <pre><%=html(statement.nativeMessage())%></pre></details>
                     </details>
                  <% } %>
               </div>
               </details>
            </div>
         </div>
      </details>
   <% } %>
<% } %>
</body>
</html>
