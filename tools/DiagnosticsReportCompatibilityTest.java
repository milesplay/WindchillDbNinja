package com.ptc.dbcapture;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.ToolProvider;

/** Isolated DTO serialization checks. No Windchill methods, connections or settings are invoked. */
public final class DiagnosticsReportCompatibilityTest {
   private static int checks;

   public static void main(String[] args) throws Exception {
      Path fixture = Path.of(args[0]);
      Path source = fixture.resolve("legacy-source/com/ptc/dbcapture/DbCaptureDiagnostics.java");
      Path classes = fixture.resolve("legacy-classes");
      Files.createDirectories(source.getParent());
      Files.createDirectories(classes);
      Files.writeString(source, """
            package com.ptc.dbcapture;
            import java.util.List;
            import com.ptc.dbcapture.diagnostics.SqlEvidencePresentation;
            import com.ptc.dbcapture.diagnostics.CaptureHealth;
            public final class DbCaptureDiagnostics {
               public record Report(String captureId, String state, String reason, String node, long startedMillis,
                     long finishedMillis, long observed, long filtered, long rejected, long failed,
                     long unfinished, long discarded, int recorded, List<String> tables,
                     List<SqlEvidencePresentation.Request> requests, String captureStatus, String mode,
                     String warnings, String error, CaptureHealth.Assessment health)
                     implements java.io.Serializable { }
               public static Report fixture() {
                  return new Report("CAP-LEGACY", "COMPLETE", "Saved evidence", "saved-node", 10, 20,
                        7, 2, 1, 0, 0, 0, 4, List.of("WTPART"), List.of(),
                        "COMPLETED", "FLASHBACK", "Saved warning", null, null);
               }
            }
            """);
      var compiler = ToolProvider.getSystemJavaCompiler();
      check(compiler != null, "Java 17 compiler is available for the independent legacy DTO fixture");
      try (var files = compiler.getStandardFileManager(null, null, null)) {
         check(compiler.getTask(null, files, null, List.of("--release", "17", "-proc:none",
               "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()), null,
               files.getJavaFileObjects(source.toFile())).call(), "the unchanged legacy record shape compiles");
      }
      try (var legacy = new URLClassLoader(new URL[] { classes.toUri().toURL() },
            DiagnosticsReportCompatibilityTest.class.getClassLoader()) {
         @Override
         protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("com.ptc.dbcapture.DbCaptureDiagnostics")
                  || name.equals("com.ptc.dbcapture.DbCaptureDiagnostics$Report")) {
               synchronized (getClassLoadingLock(name)) {
                  Class<?> loaded = findLoadedClass(name);
                  if (loaded == null) loaded = findClass(name);
                  if (resolve) resolveClass(loaded);
                  return loaded;
               }
            }
            return super.loadClass(name, resolve);
         }
      }) {
         Class<?> oldReport = legacy.loadClass("com.ptc.dbcapture.DbCaptureDiagnostics$Report");
         check(ObjectStreamClass.lookup(oldReport).getSerialVersionUID() == 0L
               && ObjectStreamClass.lookup(DbCaptureDiagnostics.Report.class).getSerialVersionUID() == 0L,
               "the default legacy record serialVersionUID is preserved explicitly");
         Object old = legacy.loadClass("com.ptc.dbcapture.DbCaptureDiagnostics").getMethod("fixture").invoke(null);
         var migrated = (DbCaptureDiagnostics.Report) deserialize(serialize(old), null);
         check(migrated.captureId().equals("CAP-LEGACY") && migrated.tables().equals(List.of("WTPART"))
               && migrated.recorded() == 4 && migrated.startedMillis() == 10 && migrated.observed() == 7,
               "old serialized report fields survive reading with the new DTO");
         check(!migrated.notRecordedScopeAvailable() && migrated.notRecordedTables().isEmpty(),
               "an absent field in a legacy stream remains explicitly unavailable, not an inferred complement");
         check(!roundTrip(migrated).notRecordedScopeAvailable(),
               "reserializing a legacy report cannot turn unknown scope into a known empty scope");

         List<String> complement = new ArrayList<>(List.of("DBCAPTURESESSION", "WTDOCUMENT"));
         var current = report(complement);
         complement.clear();
         check(current.notRecordedTables().equals(List.of("DBCAPTURESESSION", "WTDOCUMENT")),
               "report snapshots defensively copy the frozen complement");
         var restored = roundTrip(current);
         check(restored.notRecordedScopeAvailable() && restored.notRecordedTables().equals(current.notRecordedTables()),
               "a saved nonempty complement crosses the Java serialization boundary unchanged");
         check(roundTrip(report(List.of())).notRecordedScopeAvailable(),
               "known empty not-recorded scope stays distinguishable from legacy unavailability");
         try {
            restored.notRecordedTables().add("ADDED_AFTER_START");
            throw new AssertionError("not-recorded scope is mutable");
         } catch (UnsupportedOperationException expected) {
            checks++;
         }

         Object backwards = deserialize(serialize(current), legacy);
         check(oldReport.isInstance(backwards)
               && oldReport.getMethod("captureId").invoke(backwards).equals("CAP-CURRENT")
               && oldReport.getMethod("tables").invoke(backwards).equals(List.of("WTPART")),
               "an old reader can ignore the additive new field and retain the original report");
      }
      System.out.println("DiagnosticsReportCompatibilityTest: " + checks
            + " assertions passed (independent legacy/new serialized DTOs; no server changes).");
   }

   private static DbCaptureDiagnostics.Report report(List<String> notRecorded) {
      return new DbCaptureDiagnostics.Report("CAP-CURRENT", "COMPLETE", "Saved", "node", 1, 2,
            0, 0, 0, 0, 0, 0, 0, List.of("WTPART"), List.of(), "COMPLETED",
            "FLASHBACK", null, null, null, notRecorded);
   }

   private static DbCaptureDiagnostics.Report roundTrip(DbCaptureDiagnostics.Report report) throws Exception {
      return (DbCaptureDiagnostics.Report) deserialize(serialize(report), null);
   }

   private static byte[] serialize(Object object) throws Exception {
      var bytes = new ByteArrayOutputStream();
      try (var output = new ObjectOutputStream(bytes)) {
         output.writeObject(object);
      }
      return bytes.toByteArray();
   }

   private static Object deserialize(byte[] bytes, ClassLoader loader) throws Exception {
      try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
         @Override
         protected Class<?> resolveClass(ObjectStreamClass descriptor) throws java.io.IOException, ClassNotFoundException {
            if (loader != null && descriptor.getName().equals("com.ptc.dbcapture.DbCaptureDiagnostics$Report")) {
               return loader.loadClass(descriptor.getName());
            }
            return super.resolveClass(descriptor);
         }
      }) {
         return input.readObject();
      }
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
      checks++;
   }
}
