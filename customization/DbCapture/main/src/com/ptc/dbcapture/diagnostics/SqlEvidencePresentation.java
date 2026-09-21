package com.ptc.dbcapture.diagnostics;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Groups recorded call paths, never reconstructs SQL, bind values or execution results. */
public final class SqlEvidencePresentation {
   public record Statement(long sequence, long timestampMillis, String operation, String table,
                           String sql, String binds, String nativeMessage, String rawStack,
                           boolean stackTruncated, int hiddenFrames) implements Serializable {
      public String operationLabel() {
         return "INSERT".equals(operation) ? "CREATE (INSERT)" : operation;
      }
   }

   public record StatementRef(long sequence, String operation, String table) implements Serializable {
      public String label() {
         return "#" + sequence + " " + ("INSERT".equals(operation) ? "CREATE (INSERT)" : operation)
               + " " + table;
      }
   }

   public record Node(String frame, List<Node> children, List<StatementRef> statements)
         implements Serializable { }
   public record Context(String id, String target, List<Node> roots) implements Serializable { }
   public record Request(String id, String thread, String user, String uri,
                         List<Context> contexts, List<Statement> statements) implements Serializable { }
   public record SqlText(String sql, String binds) { }

   private record RequestKey(String id, String thread, String user, String uri) { }
   private static final Set<String> CLAUSES = Set.of("SET", "VALUES", "WHERE", "AND", "OR",
         "USING", "WHEN", "RETURNING");
   private static final String[] PLUMBING = {
      "com.ptc.dbcapture.diagnostics.", "org.apache.logging.log4j.", "wt.util.logger.",
      "java.", "jdk.", "sun.", "jakarta.servlet.", "org.apache.catalina.", "org.apache.coyote.",
      "org.apache.tomcat.", "wt.servlet.", "wt.httpgw.", "wt.services.ServiceFactory$",
      "wt.session.SessionContextDestroyer.", "com.ptc.core.components.filter.",
      "com.ptc.core.ui.validation.URLValidationFilter.", "com.ptc.jws.servlet.filter.",
      "com.ptc.windchill.upgrade.ui.UpgradeMaintenanceFilter.", "wt.licenseusage.licensing.LicenseFilter."
   };

   private SqlEvidencePresentation() { }

   public static List<Request> requests(List<SqlEvidence.Event> events) {
      Map<RequestKey, RequestBuilder> requests = new LinkedHashMap<>();
      for (SqlEvidence.Event event : events.stream()
            .sorted(Comparator.comparingLong(SqlEvidence.Event::getSequence)).toList()) {
         RequestKey key = new RequestKey(event.getServletRequestId(),
               event.getThreadName() + " [" + event.getThreadId() + "]",
               event.getAuthenticatedUser(), event.getRequestUri());
         requests.computeIfAbsent(key, ignored -> new RequestBuilder()).add(event);
      }
      List<Request> result = new ArrayList<>();
      requests.forEach((key, value) -> result.add(new Request(key.id(), key.thread(), key.user(), key.uri(),
            value.contexts.values().stream().map(ContextBuilder::freeze).toList(),
            List.copyOf(value.statements))));
      return List.copyOf(result);
   }

   /** Plain-text export of this request's saved tree, independent of the UI's expanded state. */
   public static String callTreeText(Request request) {
      StringBuilder text = new StringBuilder();
      text.append("Servlet request: ").append(request.id())
            .append("\nAJP thread: ").append(request.thread())
            .append("\nAuthenticated user: ").append(request.user())
            .append("\nURI: ").append(request.uri()).append('\n');
      for (Context context : request.contexts()) {
         text.append("\nMethodContext ").append(context.id()).append('\n');
         if (!context.target().isEmpty()) text.append("  Target: ").append(context.target()).append('\n');
         appendTree(text, context.roots(), 1);
      }
      return text.toString();
   }

   private static void appendTree(StringBuilder text, List<Node> nodes, int depth) {
      String indent = "  ".repeat(depth);
      for (Node node : nodes) {
         text.append(indent).append(node.frame()).append('\n');
         appendTree(text, node.children(), depth + 1);
         for (StatementRef statement : node.statements()) {
            text.append(indent).append("  ").append(statement.label()).append('\n');
         }
      }
   }

   public static SqlText sqlText(String message) {
      String sql = SqlStatementClassifier.sqlText(message);
      String binds = "";
      for (int i = 0; i < sql.length();) {
         int end = opaqueEnd(sql, i);
         if (end > i) {
            i = end;
         } else if (sql.regionMatches(true, i, ";Bind Parameters=", 0, 17)) {
            binds = sql.substring(i + 17).trim();
            sql = sql.substring(0, i);
            break;
         } else {
            i++;
         }
      }
      return new SqlText(format(sql.trim()), binds);
   }

   private static String format(String sql) {
      StringBuilder out = new StringBuilder();
      int depth = 0;
      for (int i = 0; i < sql.length();) {
         int end = opaqueEnd(sql, i);
         if (end > i) {
            out.append(sql, i, end);
            i = end;
            continue;
         }
         char c = sql.charAt(i);
         if (Character.isLetter(c)) {
            end = i + 1;
            while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end))
                  || "_$#".indexOf(sql.charAt(end)) >= 0)) end++;
            String word = sql.substring(i, end);
            if (depth == 0 && (i == 0 || ":.".indexOf(sql.charAt(i - 1)) < 0)
                  && CLAUSES.contains(word.toUpperCase(Locale.ROOT))) newline(out, 0);
            out.append(word);
            i = end;
            continue;
         }
         if (Character.isWhitespace(c)) {
            if (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) out.append(' ');
         } else {
            out.append(c);
            if (c == '(') depth++;
            if (c == ')') depth = Math.max(0, depth - 1);
            if (c == ',') {
               if (out.length() - out.lastIndexOf("\n") >= 76) newline(out, Math.min(depth, 4) * 2);
               else out.append(' ');
            }
         }
         i++;
      }
      return out.toString().trim();
   }

   private static void newline(StringBuilder out, int indent) {
      while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
      if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
      out.append(" ".repeat(indent));
   }

   /** Quoted literals, Oracle alternative quoting and comments must remain byte-for-byte unchanged. */
   private static int opaqueEnd(String text, int start) {
      if (text.startsWith("--", start)) {
         int end = text.indexOf('\n', start + 2);
         int cr = text.indexOf('\r', start + 2);
         if (cr >= 0 && (end < 0 || cr < end)) {
            return cr + 1 < text.length() && text.charAt(cr + 1) == '\n' ? cr + 2 : cr + 1;
         }
         return end < 0 ? text.length() : end + 1;
      }
      if (text.startsWith("/*", start)) {
         int end = text.indexOf("*/", start + 2);
         return end < 0 ? text.length() : end + 2;
      }
      int q = start;
      if ((text.charAt(q) == 'n' || text.charAt(q) == 'N') && q + 1 < text.length()) q++;
      if (q + 2 < text.length() && (text.charAt(q) == 'q' || text.charAt(q) == 'Q')
            && text.charAt(q + 1) == '\'') {
         char open = text.charAt(q + 2);
         char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
         int end = text.indexOf("" + close + '\'', q + 3);
         return end < 0 ? text.length() : end + 2;
      }
      char quote = text.charAt(start);
      if (quote != '\'' && quote != '"') return start;
      for (int i = start + 1; i < text.length(); i++) {
         if (text.charAt(i) == quote) {
            if (i + 1 < text.length() && text.charAt(i + 1) == quote) i++;
            else return i + 1;
         }
      }
      return text.length();
   }

   private static boolean plumbing(String frame) {
      String name = frame;
      int slash = name.lastIndexOf('/');
      if (slash >= 0) name = name.substring(slash + 1);
      for (String prefix : PLUMBING) {
         if (name.startsWith(prefix)) return true;
      }
      return false;
   }

   private static final class RequestBuilder {
      private final Map<String, ContextBuilder> contexts = new LinkedHashMap<>();
      private final List<Statement> statements = new ArrayList<>();

      private void add(SqlEvidence.Event event) {
         List<String> path = new ArrayList<>();
         List<String> stack = event.getStack();
         for (int i = stack.size() - 1; i >= 0; i--) {
            if (!plumbing(stack.get(i))) path.add(stack.get(i));
         }
         SqlText text = sqlText(event.getStatement().getNativeMessage());
         statements.add(new Statement(event.getSequence(), event.getTimestampMillis(),
               event.getStatement().getOperation(), event.getStatement().getTable(), text.sql(), text.binds(),
               event.getStatement().getNativeMessage(), String.join("\n", stack),
               event.isStackTruncated(), stack.size() - path.size()));
         ContextBuilder context = contexts.computeIfAbsent(event.getMethodContextId(),
               ignored -> new ContextBuilder(event));
         if (path.isEmpty()) path.add("No application frame recorded; inspect the full captured stack.");
         Map<String, NodeBuilder> children = context.roots;
         NodeBuilder leaf = null;
         for (String frame : path) {
            leaf = children.computeIfAbsent(frame, NodeBuilder::new);
            children = leaf.children;
         }
         leaf.statements.add(new StatementRef(event.getSequence(),
               event.getStatement().getOperation(), event.getStatement().getTable()));
      }
   }

   private static final class ContextBuilder {
      private final String id;
      private final String target;
      private final Map<String, NodeBuilder> roots = new LinkedHashMap<>();

      private ContextBuilder(SqlEvidence.Event event) {
         id = event.getMethodContextId();
         String type = event.getTargetClass();
         String method = event.getTargetMethod();
         type = type == null || type.isBlank() ? "" : type;
         method = method == null || method.isBlank() ? "" : method;
         target = type + (!type.isEmpty() && !method.isEmpty() ? "." : "") + method;
      }

      private Context freeze() {
         return new Context(id, target, roots.values().stream().map(NodeBuilder::freeze).toList());
      }
   }

   private static final class NodeBuilder {
      private final String frame;
      private final Map<String, NodeBuilder> children = new LinkedHashMap<>();
      private final List<StatementRef> statements = new ArrayList<>();

      private NodeBuilder(String frame) { this.frame = frame; }

      private Node freeze() {
         return new Node(frame, children.values().stream().map(NodeBuilder::freeze).toList(),
               List.copyOf(statements));
      }
   }
}
