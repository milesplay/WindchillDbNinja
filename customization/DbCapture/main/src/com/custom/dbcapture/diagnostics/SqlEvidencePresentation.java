package com.custom.dbcapture.diagnostics;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Groups recorded call paths, never reconstructs SQL, bind values or execution results. */
public final class SqlEvidencePresentation {
   public static final class Statement implements Serializable {
      private static final long serialVersionUID = 0L;
      private final long sequence;
      private final long timestampMillis;
      private final String operation;
      private final String table;
      private final String sql;
      private final String binds;
      private final String nativeMessage;
      private final String rawStack;
      private final boolean stackTruncated;
      private final int hiddenFrames;

      public Statement(long sequence, long timestampMillis, String operation, String table,
                       String sql, String binds, String nativeMessage, String rawStack,
                       boolean stackTruncated, int hiddenFrames) {
         this.sequence = sequence;
         this.timestampMillis = timestampMillis;
         this.operation = operation;
         this.table = table;
         this.sql = sql;
         this.binds = binds;
         this.nativeMessage = nativeMessage;
         this.rawStack = rawStack;
         this.stackTruncated = stackTruncated;
         this.hiddenFrames = hiddenFrames;
      }

      public long sequence() { return sequence; }
      public long timestampMillis() { return timestampMillis; }
      public String operation() { return operation; }
      public String table() { return table; }
      public String sql() { return sql; }
      public String binds() { return binds; }
      public String nativeMessage() { return nativeMessage; }
      public String rawStack() { return rawStack; }
      public boolean stackTruncated() { return stackTruncated; }
      public int hiddenFrames() { return hiddenFrames; }

      public String operationLabel() {
         return "INSERT".equals(operation) ? "CREATE (INSERT)" : operation;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) return true;
         if (!(other instanceof Statement)) return false;
         Statement that = (Statement) other;
         return sequence == that.sequence && timestampMillis == that.timestampMillis
               && stackTruncated == that.stackTruncated && hiddenFrames == that.hiddenFrames
               && Objects.equals(operation, that.operation) && Objects.equals(table, that.table)
               && Objects.equals(sql, that.sql) && Objects.equals(binds, that.binds)
               && Objects.equals(nativeMessage, that.nativeMessage) && Objects.equals(rawStack, that.rawStack);
      }

      @Override
      public int hashCode() {
         return Objects.hash(sequence, timestampMillis, operation, table, sql, binds, nativeMessage,
               rawStack, stackTruncated, hiddenFrames);
      }
   }

   public static final class StatementRef implements Serializable {
      private static final long serialVersionUID = 0L;
      private final long sequence;
      private final String operation;
      private final String table;

      public StatementRef(long sequence, String operation, String table) {
         this.sequence = sequence;
         this.operation = operation;
         this.table = table;
      }

      public long sequence() { return sequence; }
      public String operation() { return operation; }
      public String table() { return table; }

      public String label() {
         return "#" + sequence + " " + ("INSERT".equals(operation) ? "CREATE (INSERT)" : operation)
               + " " + table;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) return true;
         if (!(other instanceof StatementRef)) return false;
         StatementRef that = (StatementRef) other;
         return sequence == that.sequence && Objects.equals(operation, that.operation)
               && Objects.equals(table, that.table);
      }

      @Override
      public int hashCode() { return Objects.hash(sequence, operation, table); }
   }

   public static final class Node implements Serializable {
      private static final long serialVersionUID = 0L;
      private final String frame;
      private final List<Node> children;
      private final List<StatementRef> statements;

      public Node(String frame, List<Node> children, List<StatementRef> statements) {
         this.frame = frame;
         this.children = children;
         this.statements = statements;
      }

      public String frame() { return frame; }
      public List<Node> children() { return children; }
      public List<StatementRef> statements() { return statements; }

      @Override
      public boolean equals(Object other) {
         if (this == other) return true;
         if (!(other instanceof Node)) return false;
         Node that = (Node) other;
         return Objects.equals(frame, that.frame) && Objects.equals(children, that.children)
               && Objects.equals(statements, that.statements);
      }

      @Override
      public int hashCode() { return Objects.hash(frame, children, statements); }
   }

   public static final class Context implements Serializable {
      private static final long serialVersionUID = 0L;
      private final String id;
      private final String target;
      private final List<Node> roots;

      public Context(String id, String target, List<Node> roots) {
         this.id = id;
         this.target = target;
         this.roots = roots;
      }

      public String id() { return id; }
      public String target() { return target; }
      public List<Node> roots() { return roots; }

      @Override
      public boolean equals(Object other) {
         if (this == other) return true;
         if (!(other instanceof Context)) return false;
         Context that = (Context) other;
         return Objects.equals(id, that.id) && Objects.equals(target, that.target)
               && Objects.equals(roots, that.roots);
      }

      @Override
      public int hashCode() { return Objects.hash(id, target, roots); }
   }

   public static final class Request implements Serializable {
      private static final long serialVersionUID = 0L;
      private final String id;
      private final String thread;
      private final String user;
      private final String uri;
      private final List<Context> contexts;
      private final List<Statement> statements;

      public Request(String id, String thread, String user, String uri,
                     List<Context> contexts, List<Statement> statements) {
         this.id = id;
         this.thread = thread;
         this.user = user;
         this.uri = uri;
         this.contexts = contexts;
         this.statements = statements;
      }

      public String id() { return id; }
      public String thread() { return thread; }
      public String user() { return user; }
      public String uri() { return uri; }
      public List<Context> contexts() { return contexts; }
      public List<Statement> statements() { return statements; }

      @Override
      public boolean equals(Object other) {
         if (this == other) return true;
         if (!(other instanceof Request)) return false;
         Request that = (Request) other;
         return Objects.equals(id, that.id) && Objects.equals(thread, that.thread)
               && Objects.equals(user, that.user) && Objects.equals(uri, that.uri)
               && Objects.equals(contexts, that.contexts) && Objects.equals(statements, that.statements);
      }

      @Override
      public int hashCode() { return Objects.hash(id, thread, user, uri, contexts, statements); }
   }

   public static final class SqlText {
      private final String sql;
      private final String binds;

      public SqlText(String sql, String binds) {
         this.sql = sql;
         this.binds = binds;
      }

      public String sql() { return sql; }
      public String binds() { return binds; }

      @Override
      public boolean equals(Object other) {
         if (this == other) return true;
         if (!(other instanceof SqlText)) return false;
         SqlText that = (SqlText) other;
         return Objects.equals(sql, that.sql) && Objects.equals(binds, that.binds);
      }

      @Override
      public int hashCode() { return Objects.hash(sql, binds); }
   }

   private static final class RequestKey {
      private final String id;
      private final String thread;
      private final String user;
      private final String uri;

      private RequestKey(String id, String thread, String user, String uri) {
         this.id = id;
         this.thread = thread;
         this.user = user;
         this.uri = uri;
      }

      private String id() { return id; }
      private String thread() { return thread; }
      private String user() { return user; }
      private String uri() { return uri; }

      @Override
      public boolean equals(Object other) {
         if (this == other) return true;
         if (!(other instanceof RequestKey)) return false;
         RequestKey that = (RequestKey) other;
         return Objects.equals(id, that.id) && Objects.equals(thread, that.thread)
               && Objects.equals(user, that.user) && Objects.equals(uri, that.uri);
      }

      @Override
      public int hashCode() { return Objects.hash(id, thread, user, uri); }
   }
   private static final Set<String> CLAUSES = Set.of("SET", "VALUES", "WHERE", "AND", "OR",
         "USING", "WHEN", "RETURNING");
   private static final String[] PLUMBING = {
      "com.custom.dbcapture.diagnostics.", "org.apache.logging.log4j.", "wt.util.logger.",
      "java.", "jdk.", "sun.", "javax.servlet.", "org.apache.catalina.", "org.apache.coyote.",
      "org.apache.tomcat.", "wt.servlet.", "wt.httpgw.", "wt.services.ServiceFactory$",
      "wt.session.SessionContextDestroyer.", "com.ptc.core.components.filter.",
      "com.ptc.core.ui.validation.URLValidationFilter.", "com.ptc.jws.servlet.filter.",
      "com.ptc.windchill.upgrade.ui.UpgradeMaintenanceFilter.", "wt.licenseusage.licensing.LicenseFilter."
   };

   private SqlEvidencePresentation() { }

   public static List<Request> requests(List<SqlEvidence.Event> events) {
      Map<RequestKey, RequestBuilder> requests = new LinkedHashMap<>();
      for (SqlEvidence.Event event : events.stream()
         .sorted(Comparator.comparingLong(SqlEvidence.Event::getSequence)).collect(Collectors.toList())) {
         RequestKey key = new RequestKey(event.getServletRequestId(),
               event.getThreadName() + " [" + event.getThreadId() + "]",
               event.getAuthenticatedUser(), event.getRequestUri());
         requests.computeIfAbsent(key, ignored -> new RequestBuilder()).add(event);
      }
      List<Request> result = new ArrayList<>();
      requests.forEach((key, value) -> result.add(new Request(key.id(), key.thread(), key.user(), key.uri(),
         value.contexts.values().stream().map(ContextBuilder::freeze).collect(Collectors.toList()),
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
         char close;
         switch (open) {
            case '[': close = ']'; break;
            case '(': close = ')'; break;
            case '{': close = '}'; break;
            case '<': close = '>'; break;
            default: close = open;
         }
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
         return new Context(id, target,
            roots.values().stream().map(NodeBuilder::freeze).collect(Collectors.toList()));
      }
   }

   private static final class NodeBuilder {
      private final String frame;
      private final Map<String, NodeBuilder> children = new LinkedHashMap<>();
      private final List<StatementRef> statements = new ArrayList<>();

      private NodeBuilder(String frame) { this.frame = frame; }

      private Node freeze() {
         return new Node(frame, children.values().stream().map(NodeBuilder::freeze).collect(Collectors.toList()),
               List.copyOf(statements));
      }
   }
}
