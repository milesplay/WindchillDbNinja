package com.custom.dbcapture;

import com.custom.dbcapture.engine.CapturedChange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import wt.util.WTException;

/** Pure ID allocation and pre-persistence validation; no persistence manager is invoked. */
public final class CaptureHelperTest {
   private static int assertions;

   public static void main(String[] args) throws Exception {
      check(next().equals("CAP-000001"), "empty history starts at one");
      check(next("CAP-000009", "CAP-000002").equals("CAP-000010"), "ordinary padded IDs retain their format");
      check(next("CAP-999999", "CAP-1000000", "CAP-999998").equals("CAP-1000001"),
            "numeric maximum wins when lexical ordering reverses at one million");
      check(next("CAP-10000000", "CAP-9999999", "CAP-10000001").equals("CAP-10000002"),
            "allocation is independent of iteration order at later digit boundaries");
      check(next(null, "", "OTHER-9999999", "CAP--9", "CAP-1x", "CAP-+9", "CAP-000042").equals("CAP-000043"),
            "only exact CAP decimal IDs contribute to the maximum");
      check(next("CAP-9223372036854775807", "CAP-9223372036854775808").equals("CAP-9223372036854775809"),
            "large persisted decimal capture IDs never wrap signed-long arithmetic");
      String largest = "CAP-" + "9".repeat(36);
      expectFailure(() -> next(largest), "40-character",
            "exhausted capture ID width fails explicitly instead of overflowing or reusing an ID");
      Locale old = Locale.getDefault();
      try {
         for (Locale locale : List.of(Locale.US, Locale.JAPAN, Locale.forLanguageTag("ar-EG"))) {
            Locale.setDefault(locale);
            check(next("CAP-999999", "CAP-1000000").equals("CAP-1000001"),
                  "generated capture IDs remain canonical ASCII under " + locale);
         }
      } finally {
         Locale.setDefault(old);
      }

      for (String name : Arrays.asList(null, "", "T".repeat(41))) {
         CapturedChange change = change(name);
         expectFailure(() -> DbCaptureHelper.storeChanges(null, List.of(change)), "never truncated",
               "invalid table names fail before session access or persistence");
      }
      for (String name : Arrays.asList(null, "", "C".repeat(40) + "X", "C".repeat(40) + "Y")) {
         CapturedChange change = change("WTPART");
         addDelta(change, name);
         expectFailure(() -> DbCaptureHelper.storeChanges(null, List.of(change)), "never truncated",
               "invalid/colliding column names cannot be clipped into persisted aliases");
      }
      CapturedChange valid = change("WTPART");
      addDelta(valid, "NAME");
      CapturedChange invalid = change("T".repeat(41));
      expectFailure(() -> DbCaptureHelper.storeChanges(null, List.of(valid, invalid)), "never truncated",
            "the entire change list is identifier-validated before any parent/child write");
      System.out.println("PASS: " + assertions + " capture helper/identifier assertions; no database writes.");
   }

   private static String next(String... ids) throws Exception {
      List<DbCaptureSession> sessions = new ArrayList<>();
      for (String id : ids) {
         sessions.add(new DbCaptureSession() {
            @Override public String getCaptureId() { return id; }
         });
      }
      return DbCaptureHelper.nextCaptureId(Collections.enumeration(sessions));
   }

   private static CapturedChange change(String table) throws Exception {
      var constructor = CapturedChange.class.getDeclaredConstructor(String.class, long.class);
      constructor.setAccessible(true);
      return constructor.newInstance(table, 1L);
   }

   private static void addDelta(CapturedChange change, String name) throws Exception {
      var method = CapturedChange.class.getDeclaredMethod("addDelta", String.class, String.class, String.class);
      method.setAccessible(true);
      method.invoke(change, name, "before", "after");
   }

   private static void expectFailure(Action action, String detail, String label) throws Exception {
      try {
         action.run();
         throw new AssertionError(label);
      } catch (WTException expected) {
         check(expected.getMessage().contains(detail), label);
      }
   }

   private static void check(boolean value, String label) {
      if (!value) throw new AssertionError(label);
      assertions++;
   }

   private interface Action { void run() throws Exception; }
}
