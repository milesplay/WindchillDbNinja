<%--
  JSON endpoint for the header buttons: reports the capture state and performs
  start, stop and abort.

  The JSP runs in the web tier, but every operation goes through
  DbCaptureService, which is a Windchill manager service - so the work itself
  executes in the method server, where a method context (and therefore a
  datastore connection) exists. Authorization is enforced inside the service,
  not here, so it applies however the request arrived.
--%><%@ page contentType="application/json; charset=UTF-8" trimDirectiveWhitespaces="true"
%><%@ page import="com.ptc.dbcapture.DbCaptureHelper"
%><%@ page import="com.ptc.dbcapture.DbCaptureSession"
%><%@ page import="com.ptc.dbcapture.engine.TableFilter"
%><%@ page import="java.util.ArrayList"
%><%@ page import="java.util.List"
%><%
   response.setHeader("Cache-Control", "no-store");

   String op = request.getParameter("op");
   String message = null;
   boolean ok = true;
   String completedCaptureId = null;
   String resultsUrl = null;
   com.ptc.dbcapture.engine.MonitoringScope.Selection scopeSelection = null;
   java.util.Map<String, String> exportNames = null;

   // Filled in only by op=tables; see the branch below for why they exist.
   List<String> knownTables = null;
   List<String> availableTables = null;
   List<String> hiddenTables = null;
   List<String> unknownTables = null;

   try {
      if ("start".equals(op)) {
         DbCaptureSession started =
               DbCaptureHelper.service.startCapture(boundedDescription(request.getParameter("label")));
         message = "DB capture " + started.getCaptureId() + " started.";
         if (started.getWarnings() != null) message += " " + started.getWarnings();
      } else if ("stop".equals(op)) {
         DbCaptureSession stopped = DbCaptureHelper.service.stopCapture();
         message = DbCaptureHelper.completionMessage(stopped);
         completedCaptureId = stopped.getCaptureId();
         resultsUrl = com.ptc.dbcapture.DbCaptureNavigation.resultsUrl(completedCaptureId);
      } else if ("scope".equals(op)) {
         scopeSelection = com.ptc.dbcapture.DbCaptureMonitoringScope.read();
      } else if ("settings".equals(op)) {
         scopeSelection = com.ptc.dbcapture.DbCaptureMonitoringScope.update(
               request.getParameter(com.ptc.dbcapture.DbCaptureSettings.INCLUDED_TABLES));
         message = "Monitoring scope saved.";
      } else if ("exportNames".equals(op)) {
         com.ptc.dbcapture.DbCaptureAuthorization.requireAdministrator();
         List<String> captureIds = exportCaptureIds(request.getParameter("captureIds"));
         java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
         for (String captureId : captureIds) {
            DbCaptureSession exportSession = DbCaptureHelper.findSession(captureId);
            if (exportSession == null) {
               throw new wt.util.WTException("Capture session not found: " + captureId + ".");
            }
            names.put(captureId, exportSession.getDescription());
         }
         exportNames = names;
      } else if ("tables".equals(op)) {
         /*
          * What the hide-tables filter is allowed to hide, and what the
          * current selection actually hides.
          *
          * Resolved here, in Java, by the same TableFilter the results builder
          * uses - so the list the page shows back to the reader is the list
          * that was applied, and not a second implementation of the same
          * patterns that can drift away from it. A forensic tool must not drop
          * rows without saying which.
          */
         com.ptc.dbcapture.DbCaptureAuthorization.requireAdministrator();
         String scope = trimToNull(request.getParameter("captureId"));
         java.util.Set<String> visibleCaptures = DbCaptureHelper.visibleCaptureIds(
               "true".equalsIgnoreCase(request.getParameter("showUnfinished")));
         availableTables = DbCaptureHelper.capturedTableNames(visibleCaptures, null);
         knownTables = scope == null ? availableTables
               : DbCaptureHelper.capturedTableNames(visibleCaptures, scope);

         // Typed entries are exact table names, not patterns. Anything that
         // does not name a captured table is reported rather than silently
         // matching nothing - which is indistinguishable, on screen, from a
         // filter that worked.
         List<String> typed = TableFilter.splitPatterns(request.getParameter("tables"));
         unknownTables = new ArrayList<String>();
         for (String name : typed) {
            if (!availableTables.contains(name)) {
               unknownTables.add(name);
            }
         }

         List<String> patterns = TableFilter.patternsForGroups(
               TableFilter.splitKeys(request.getParameter("groups")));
         patterns.addAll(typed);
         hiddenTables = TableFilter.resolveHidden(knownTables, patterns);
      } else if ("describe".equals(op)) {
         String sessionOid = trimToNull(request.getParameter("sessionOid"));
         String description = boundedDescription(request.getParameter("description"));
         if (sessionOid != null) {
            DbCaptureHelper.service.setDescriptionByOid(
                  sessionOid, description);
         } else {
            DbCaptureHelper.service.setDescription(request.getParameter("captureId"),
                                                   description);
         }
         message = "Description saved.";
      } else if ("delete".equals(op)) {
         DbCaptureHelper.service.deleteCaptureByOid(request.getParameter("sessionOid"));
         message = "Capture session deleted.";
      } else if (op != null && !op.trim().isEmpty()) {
         throw new wt.util.WTException("Unsupported DB Capture operation: " + op + ".");
      }
   } catch (Exception e) {
      ok = false;
      message = e.getMessage();
      // The dialog is transient; make sure the failure is also in the log.
      org.apache.logging.log4j.LogManager
         .getLogger("com.ptc.dbcapture.endpoint")
         .error("DB Capture endpoint failed for op=" + op, e);
   }

   com.ptc.dbcapture.DbCaptureBannerState banner = null;
   try {
      banner = DbCaptureHelper.service.getBannerState();
   } catch (Exception e) {
      ok = false;
      message = e.getMessage();
      org.apache.logging.log4j.LogManager.getLogger("com.ptc.dbcapture.endpoint")
            .error("Could not read DB Capture banner state.", e);
   }

   StringBuilder json = new StringBuilder();
   json.append('{');
   json.append("\"ok\":").append(ok);
   json.append(",\"stateKnown\":").append(banner != null);
   json.append(",\"running\":").append(banner != null && banner.running());
   json.append(",\"captureId\":").append(quote(banner == null ? null : banner.captureId()));
   json.append(",\"label\":null");
   json.append(",\"startedBy\":").append(quote(banner == null ? null : banner.startedBy()));
   json.append(",\"startedAtMillis\":").append(banner == null ? 0L : banner.startedAtMillis());
   json.append(",\"administrator\":").append(banner != null && banner.administrator());
   json.append(",\"ownedByCurrentUser\":").append(
         banner != null && banner.ownedByCurrentUser());
   json.append(",\"canStart\":").append(banner != null && banner.canStart());
   json.append(",\"canStop\":").append(banner != null && banner.canStop());
   json.append(",\"message\":").append(quote(message));
   json.append(",\"completedCaptureId\":").append(quote(completedCaptureId));
   json.append(",\"resultsUrl\":").append(quote(resultsUrl));

   // Catalog work is strictly on demand, never part of ordinary banner polls.
   if (scopeSelection != null) {
      json.append(",\"scopeExcluded\":").append(array(scopeSelection.getExcluded()));
      json.append(",\"scopeIncluded\":").append(array(scopeSelection.getIncluded()));
      json.append(",\"scopeLocked\":").append(array(scopeSelection.getLocked()));
      json.append(",\"scopeReasons\":").append(object(scopeSelection.getReasons()));
      json.append(",\"scopeNotice\":").append(quote(scopeSelection.getNotice()));
   }
   if (exportNames != null) {
      json.append(",\"exportNames\":").append(object(exportNames));
   }

   // Only op=tables fills these; every other op leaves the three keys out.
   if (knownTables != null) {
      json.append(",\"known\":").append(array(knownTables));
      json.append(",\"available\":").append(array(availableTables));
      json.append(",\"hidden\":").append(array(hiddenTables));
      json.append(",\"unknown\":").append(array(unknownTables));
   }
   json.append('}');
   out.print(json.toString());
%><%!
   private static List<String> exportCaptureIds(String raw) {
      if (raw == null || raw.trim().isEmpty()) {
         throw new IllegalArgumentException("captureIds must contain at least one capture ID.");
      }
      String[] tokens = raw.split(",", 501);
      if (tokens.length > 500) {
         throw new IllegalArgumentException("At most 500 distinct capture IDs may be requested.");
      }
      java.util.Set<String> ids = new java.util.LinkedHashSet<>();
      for (String token : tokens) {
         String id = token.trim();
         if (!id.matches("CAP-[0-9]{1,36}")) {
            throw new IllegalArgumentException(
                  "Invalid capture ID. Expected CAP- followed by 1 to 36 digits; empty entries are not allowed.");
         }
         if (!ids.add(id)) {
            throw new IllegalArgumentException("Duplicate capture ID: " + id + ".");
         }
      }
      return List.copyOf(ids);
   }

   private static String boundedDescription(String value) throws wt.util.WTException {
      if (value != null && value.length() > 400) {
         throw new wt.util.WTException("Description must be 400 characters or fewer. Nothing was saved.");
      }
      return value;
   }

   /** Empty string and absent mean the same thing to every caller here. */
   private static String trimToNull(String value) {
      if (value == null) {
         return null;
      }
      String trimmed = value.trim();
      return trimmed.isEmpty() ? null : trimmed;
   }

   /** A JSON array of strings. */
   private static String array(java.util.List<String> values) {
      StringBuilder sb = new StringBuilder();
      sb.append('[');
      if (values != null) {
         for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
               sb.append(',');
            }
            sb.append(quote(values.get(i)));
         }
      }
      sb.append(']');
      return sb.toString();
   }

   private static String object(java.util.Map<String, String> values) {
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
         if (!first) sb.append(',');
         first = false;
         sb.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
      }
      return sb.append('}').toString();
   }

   /** Minimal JSON string escaping; these values are short identifiers and messages. */
   private static String quote(String value) {
      if (value == null) {
         return "null";
      }
      StringBuilder sb = new StringBuilder(value.length() + 2);
      sb.append('"');
      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '"':  sb.append("\\\""); break;
            case '\\': sb.append("\\\\"); break;
            case '\n': sb.append("\\n");  break;
            case '\r': sb.append("\\r");  break;
            case '\t': sb.append("\\t");  break;
            default:
               if (c < 0x20) {
                  sb.append(String.format("\\u%04x", (int) c));
               } else {
                  sb.append(c);
               }
         }
      }
      sb.append('"');
      return sb.toString();
   }
%>
