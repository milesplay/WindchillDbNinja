package com.custom.dbcapture;

import com.custom.dbcapture.engine.CaptureEngine;
import com.custom.dbcapture.engine.TableFilter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import wt.util.WTException;
import wt.util.WTProperties;

/**
 * Runs the real compiled service with classloader-local JDBC, persistence,
 * authorization and logging boundary doubles. No MethodServer, database,
 * logger configuration or diagnostics store is invoked. Mock compilation
 * explicitly disables annotation processing and writes only below the supplied
 * project-local output directory.
 */
public final class CaptureLifecycleTest {
   private static int assertions;
   public static Connection connection;
   public static DbCaptureSession current;
   public static final Properties correlationSettings = new Properties();
   public static boolean correlationReadFailure;
   public static boolean administrator = true;
   public static String user = "owner";
   public static int connections, saves, stores, opens, closes;
   public static RuntimeException saveFailure;
   public static List<String> includedTables, physicalTables;

   public static void main(String[] args) throws Exception {
      if (args.length != 1) throw new IllegalArgumentException("Supply a new project-local fixture output.");
      Path root = Path.of(args[0]).toAbsolutePath().normalize();
      if (!root.startsWith(Path.of("").toAbsolutePath().normalize()) || Files.exists(root)) {
         throw new IllegalArgumentException("Fixture output must be new and below the working directory.");
      }
      Files.createDirectory(root);
      Path home = root.resolve("home");
      Properties properties = WTProperties.getLocalProperties();
      Map<String, Object> previous = new LinkedHashMap<>();
      for (String name : List.of("wt.home", TableFilter.EXCLUDE_PROPERTY, "com.custom.dbcapture.maxRowsPerTable")) {
         previous.put(name, properties.get(name));
      }
      try (URLClassLoader loader = serviceLoader(root)) {
         properties.setProperty("wt.home", home.toString());
         properties.setProperty(TableFilter.EXCLUDE_PROPERTY, "");
         properties.setProperty("com.custom.dbcapture.maxRowsPerTable", "5000");
         Class<?> serviceClass = loader.loadClass("com.custom.dbcapture.StandardDbCaptureService");
         correlation(serviceClass);
         lifecycle(serviceClass, home);
      } finally {
         previous.forEach((key, value) -> {
            if (value == null) properties.remove(key); else properties.put(key, value);
         });
         if (Files.exists(home)) {
            try (var files = Files.walk(home)) {
               for (Path file : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(file);
            }
         }
      }
      System.out.println("PASS: " + assertions + " isolated service lifecycle/logging assertions; no database/server writes.");
   }

   private static void correlation(Class<?> serviceClass) throws Exception {
      Method enabled = serviceClass.getDeclaredMethod("isCorrelationEnabled");
      enabled.setAccessible(true);
      correlationSettings.clear();
      check(Boolean.FALSE.equals(invoke(enabled, null)), "missing legacy logging opt-in is disabled");
      for (String raw : List.of("", "false", " false ", "yes", "0", "perhaps", "true", " TRUE ")) {
         correlationSettings.setProperty("com.custom.dbcapture.correlateLogs", raw);
         check(Boolean.valueOf(raw.trim().equalsIgnoreCase("true")).equals(invoke(enabled, null)),
               "only explicit true enables legacy logging: [" + raw + "]");
      }
      correlationReadFailure = true;
      try {
         check(Boolean.FALSE.equals(invoke(enabled, null)), "settings read exceptions disable legacy logging");
      } finally {
         correlationReadFailure = false;
         correlationSettings.clear();
      }
   }

   private static void lifecycle(Class<?> serviceClass, Path home) throws Exception {
      reset();
      Jdbc jdbc = new Jdbc();
      connection = jdbc.connection();
      Object service = serviceClass.getConstructor().newInstance();
      DbCaptureSession started = (DbCaptureSession) invoke(serviceClass.getMethod("startCapture", String.class),
            service, "offline fixture");
      CaptureEngine.CaptureBaseline frozen = (CaptureEngine.CaptureBaseline) field(service, "baseline");
      check(jdbc.catalogReads == 1 && frozen.getCatalog().getTableNames().equals(physicalTables)
            && frozen.getCatalog().includedTables(frozen.getScope()).equals(includedTables)
            && includedTables.equals(List.of("WTPART")), "Start shares exactly one catalog with diagnostics and collection");
      check(opens == 0, "Start with no explicit opt-in never opens the legacy SQL log window");
      started.setStartedBy("owner");
      Path settings = home.resolve("custom/DbCapture/settings.properties");
      Files.createDirectories(settings.getParent());
      Files.writeString(settings, "includedTables=NOT_AN_OPERATIONAL_TABLE\n");
      jdbc.scn = 200;
      DbCaptureSession stopped = (DbCaptureSession) invoke(serviceClass.getMethod("stopCapture"), service);
      check(!DbCaptureSession.STATUS_RUNNING.equals(stopped.getStatus())
            && !DbCaptureSession.STATUS_FAILED.equals(stopped.getStatus()) && stopped.getEndTime() != null,
            "Stop with a frozen scope succeeds even after current settings become invalid");
      check(stores == 1 && jdbc.catalogReads == 1, "Stop stores one result without re-reading the physical catalog");
      check(field(service, "baseline") == null && field(service, "requestEvidenceIssue") == null && closes == 1,
            "successful Stop releases baseline, diagnostics state and legacy logger window");

      for (String mode : List.of("invalid", "unreadable", "failed-status-save")) {
         Files.deleteIfExists(settings);
         if (mode.equals("unreadable")) Files.createDirectory(settings);
         else Files.writeString(settings, "includedTables=NOT_AN_OPERATIONAL_TABLE\n");
         reset();
         jdbc = new Jdbc();
         connection = jdbc.connection();
         current = runningSession();
         service = serviceClass.getConstructor().newInstance();
         setField(service, "baseline", CaptureEngine.baselineFromScn(100, "lost scope fixture"));
         setField(service, "requestEvidenceIssue", "fixture diagnostics unavailable");
         Object window = field(service, "logWindow");
         window.getClass().getMethod("open").invoke(window);
         if (mode.equals("failed-status-save")) saveFailure = new IllegalStateException("synthetic FAILED-status save failure");
         WTException failure = stopFailure(serviceClass, service);
         Throwable original = failure.getCause();
         check(original instanceof IllegalArgumentException || original instanceof UncheckedIOException,
               "Stop retains the original settings exception: " + mode);
         check(current.getStatus().equals(DbCaptureSession.STATUS_FAILED) && current.getEndTime() != null
               && current.getErrorText().contains(original.getMessage()) && saves == 1 && stores == 0,
               "settings failure records FAILED rather than leaving a stale RUNNING result: " + mode);
         check(field(service, "baseline") == null && field(service, "requestEvidence") == null
               && field(service, "requestEvidenceIssue") == null
               && Boolean.FALSE.equals(window.getClass().getMethod("isOpen").invoke(window)) && closes == 1,
               "settings failure still cleans owned lifecycle state: " + mode);
         if (saveFailure != null) {
            check(original.getSuppressed().length == 1 && original.getSuppressed()[0] == saveFailure,
                  "FAILED-status save errors are suppressed on, not substituted for, the original failure");
         }
      }

      for (String denied : List.of("administrator", "owner", "owner-case")) {
         reset();
         jdbc = new Jdbc();
         connection = jdbc.connection();
         current = runningSession();
         service = serviceClass.getConstructor().newInstance();
         setField(service, "baseline", frozen);
         setField(service, "requestEvidenceIssue", "must remain owned");
         administrator = !denied.equals("administrator");
         user = denied.equals("owner-case") ? "Owner" : "other";
         stopFailure(serviceClass, service);
         check(field(service, "baseline") == frozen
               && "must remain owned".equals(field(service, "requestEvidenceIssue"))
               && saves == 0 && stores == 0 && closes == 0
               && current.getStatus().equals(DbCaptureSession.STATUS_RUNNING),
               "unauthorized Stop cannot clean up or mutate another owner's capture: " + denied);
         if (!administrator) check(connections == 0, "administrator guard runs before acquiring any database connection");
      }
   }

   private static WTException stopFailure(Class<?> type, Object service) throws Exception {
      try {
         invoke(type.getMethod("stopCapture"), service);
         throw new AssertionError("Expected Stop failure");
      } catch (WTException expected) {
         return expected;
      }
   }

   private static DbCaptureSession runningSession() throws Exception {
      DbCaptureSession session = new DbCaptureSession();
      session.setCaptureId("CAP-000041");
      session.setStartedBy("owner");
      session.setStatus(DbCaptureSession.STATUS_RUNNING);
      session.setStartScn(100);
      session.setStartTime(Timestamp.valueOf("2026-09-21 00:00:00"));
      return session;
   }

   public static void requireAdministrator() throws WTException {
      if (!administrator) throw new WTException("synthetic administrator denial");
   }

   public static void requireOwner(DbCaptureSession session) throws WTException {
      if (!DbCaptureAuthorization.isOwner(session, user)) throw new WTException("synthetic owner denial");
   }

   public static DbCaptureSession save(DbCaptureSession session) {
      saves++;
      if (saveFailure != null) throw saveFailure;
      current = session;
      return session;
   }

   private static void reset() {
      current = null;
      administrator = true;
      user = "owner";
      connections = saves = stores = opens = closes = 0;
      saveFailure = null;
   }

   private static Object field(Object object, String name) throws Exception {
      Field field = object.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(object);
   }

   private static void setField(Object object, String name, Object value) throws Exception {
      Field field = object.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(object, value);
   }

   private static Object invoke(Method method, Object object, Object... args) throws Exception {
      try {
         return method.invoke(object, args);
      } catch (InvocationTargetException failure) {
         if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
         throw (Error) failure.getCause();
      }
   }

   private static URLClassLoader serviceLoader(Path root) throws Exception {
      Map<String, String> stubs = new LinkedHashMap<>();
      stubs.put("com.custom.dbcapture.DbCaptureAuthorization", source(
         "package com.custom.dbcapture;",
         "public class DbCaptureAuthorization {",
         "  public static void requireAdministrator() throws wt.util.WTException { CaptureLifecycleTest.requireAdministrator(); }",
         "  public static void requireOwner(DbCaptureSession s) throws wt.util.WTException { CaptureLifecycleTest.requireOwner(s); }",
         "}"));
      stubs.put("com.custom.dbcapture.DbCaptureJdbc", source(
         "package com.custom.dbcapture;",
         "public class DbCaptureJdbc {",
         "  public static java.sql.Connection connection() { CaptureLifecycleTest.connections++; return CaptureLifecycleTest.connection; }",
         "}"));
      stubs.put("com.custom.dbcapture.DbCaptureHelper", source(
         "package com.custom.dbcapture;",
         "public class DbCaptureHelper {",
         "  public static DbCaptureSession findRunningSession() { return CaptureLifecycleTest.current; }",
         "  public static DbCaptureSession findSession(long id) {",
         "    if (id != 41) throw new AssertionError(\"Wrong locked session identity\");",
         "    return CaptureLifecycleTest.current;",
         "  }",
         "  public static String nextCaptureId() { return \"CAP-000041\"; }",
         "  public static DbCaptureSession save(DbCaptureSession s) { return CaptureLifecycleTest.save(s); }",
         "  public static void storeChanges(DbCaptureSession s, java.util.List<com.custom.dbcapture.engine.CapturedChange> changes) {",
         "    CaptureLifecycleTest.stores++;",
         "  }",
         "}"));
      stubs.put("com.custom.dbcapture.DbCaptureDiagnostics", source(
         "package com.custom.dbcapture;",
         "public class DbCaptureDiagnostics {",
         "  public static com.custom.dbcapture.diagnostics.SqlEvidenceCapture begin(",
         "        DbCaptureSession s, java.util.List<String> included, java.util.List<String> physical) {",
         "    CaptureLifecycleTest.includedTables = java.util.List.copyOf(included);",
         "    CaptureLifecycleTest.physicalTables = java.util.List.copyOf(physical);",
         "    return null;",
         "  }",
         "  public static String warning(DbCaptureSession s) { return null; }",
         "}"));
      stubs.put("com.custom.dbcapture.engine.SqlLogWindow", source(
         "package com.custom.dbcapture.engine;",
         "public class SqlLogWindow {",
         "  private boolean open;",
         "  public void open() { open = true; com.custom.dbcapture.CaptureLifecycleTest.opens++; }",
         "  public void close() { open = false; com.custom.dbcapture.CaptureLifecycleTest.closes++; }",
         "  public boolean isOpen() { return open; }",
         "}"));
      stubs.put("wt.util.WTProperties", source(
         "package wt.util;",
         "public class WTProperties extends java.util.Properties {",
         "  public static WTProperties getLocalProperties() throws java.io.IOException {",
         "    if (com.custom.dbcapture.CaptureLifecycleTest.correlationReadFailure) throw new java.io.IOException(\"synthetic settings read failure\");",
         "    WTProperties properties = new WTProperties();",
         "    properties.putAll(com.custom.dbcapture.CaptureLifecycleTest.correlationSettings);",
         "    return properties;",
         "  }",
         "}"));
      stubs.put("wt.session.SessionHelper", source(
         "package wt.session;",
         "public class SessionHelper { public static SessionManager manager; }"));
      List<JavaFileObject> sources = new ArrayList<>();
      stubs.forEach((name, content) -> sources.add(new SimpleJavaFileObject(
            URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
         @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
      }));
      Path mocks = Files.createDirectory(root.resolve("boundary-classes"));
      var compiler = ToolProvider.getSystemJavaCompiler();
      if (compiler == null) throw new IllegalStateException("This test requires the selected JDK, not a JRE.");
      StringWriter messages = new StringWriter();
      try (var files = compiler.getStandardFileManager(null, null, null)) {
         boolean compiled = compiler.getTask(messages, files, null,
               List.of("--release", "17", "-proc:none", "-encoding", "UTF-8",
                  "-classpath", System.getProperty("java.class.path"), "-d", mocks.toString()),
               null, sources).call();
         if (!compiled) throw new AssertionError(messages.toString());
      }
      URL sourceClasses = CaptureEngine.class.getProtectionDomain().getCodeSource().getLocation();
      return new URLClassLoader(new URL[] {mocks.toUri().toURL(), sourceClasses}, CaptureLifecycleTest.class.getClassLoader()) {
         @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
               if (name.equals("com.custom.dbcapture.StandardDbCaptureService")
                     || stubs.containsKey(name)) {
                  Class<?> loaded = findLoadedClass(name);
                  if (loaded == null) loaded = findClass(name);
                  if (resolve) resolveClass(loaded);
                  return loaded;
               }
               return super.loadClass(name, resolve);
            }
         }
      };
   }

   private static String source(String... lines) {
      return String.join("\n", lines) + "\n";
   }

   private static final class Jdbc {
      private int catalogReads;
      private long scn = 100;

      private Connection connection() {
         return proxy(Connection.class, (p, method, args) -> {
            switch (method.getName()) {
               case "createStatement": return statement(null);
               case "prepareStatement": return statement((String) args[0]);
               default: throw new AssertionError("Connection ownership/transaction calls are forbidden: " + method.getName());
            }
         });
      }

      private Statement statement(String sql) {
         java.lang.reflect.InvocationHandler handler = (p, method, args) -> {
            switch (method.getName()) {
               case "setQueryTimeout":
               case "setFetchSize":
               case "setString":
               case "setTimestamp":
               case "close": return null;
               case "execute":
                  String command = (String) args[0];
                  if (!command.equals("LOCK TABLE DBCAPTURESESSION IN EXCLUSIVE MODE NOWAIT")
                        && !command.equals("BEGIN DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO; END;")) {
                     throw new AssertionError("Unexpected simulated command: " + command);
                  }
                  return false;
               case "executeQuery": return query(sql == null ? (String) args[0] : sql);
               default: throw new AssertionError("Unexpected JDBC operation: " + method.getName());
            }
         };
         return sql == null ? proxy(Statement.class, handler) : proxy(PreparedStatement.class, handler);
      }

      private ResultSet query(String sql) {
         if (!sql.startsWith("SELECT ")) throw new AssertionError("Only SELECT queries are allowed: " + sql);
         if (sql.contains("DBMS_FLASHBACK")) return rows(new Object[][] {{scn}});
         if (sql.contains("FOR UPDATE NOWAIT")) return rows(new Object[][] {{41L}});
         if (sql.contains("SYSTIMESTAMP")) return rows(new Object[][] {{"+00:00"}});
         if (sql.contains("USER_TABLES")) {
            catalogReads++;
            return rows(new Object[][] {{"WTPART", 1}, {"QuotedTable", 1}, {"DBCAPTURESESSION", 1}});
         }
         if (sql.contains("USER_TAB_MODIFICATIONS")) return rows(new Object[0][]);
         throw new AssertionError("Unexpected lifecycle query: " + sql);
      }
   }

   private static ResultSet rows(Object[][] values) {
      int[] index = {-1};
      return proxy(ResultSet.class, (p, method, args) -> {
         switch (method.getName()) {
            case "next": return ++index[0] < values.length;
            case "close": return null;
            case "getLong": return ((Number) values[index[0]][(int) args[0] - 1]).longValue();
            case "getInt": return ((Number) values[index[0]][(int) args[0] - 1]).intValue();
            case "getString": return values[index[0]][(int) args[0] - 1].toString();
            default: throw new AssertionError("Unexpected fixture result operation: " + method.getName());
         }
      });
   }

   private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
      return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
   }

   private static void check(boolean value, String label) {
      if (!value) throw new AssertionError(label);
      assertions++;
   }
}
