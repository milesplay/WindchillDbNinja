package com.ptc.dbcapture.diagnostics;

import java.util.List;

public final class SqlEvidencePresentationTest {
   private static int checks;

   public static void main(String[] args) throws Exception {
      treeGrouping();
      callTreeExport();
      deepestSupportedStack();
      sqlFormatting();
      health();
      System.out.println("SqlEvidencePresentationTest: " + checks
            + " assertions passed (SQL text, exact request/context grouping, trees and health).");
   }

   private static void deepestSupportedStack() throws Exception {
      List<String> stack = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> "example.Frame" + i + ".call(Frame.java:" + i + ")").toList();
      var request = SqlEvidencePresentation.requests(List.of(event(1, "deep", "deep-context",
            "UPDATE", stack, true))).get(0);
      var node = request.contexts().get(0).roots().get(0);
      int depth = 1;
      while (!node.children().isEmpty()) {
         node = node.children().get(0);
         depth++;
      }
      check(depth == 256 && node.statements().size() == 1,
            "maximum supported stack depth retains the issuing SQL without overflowing");
      var bytes = new java.io.ByteArrayOutputStream();
      try (var output = new java.io.ObjectOutputStream(bytes)) {
         output.writeObject(request);
      }
      check(bytes.size() > 0, "deepest supported tree can cross the serialization boundary");
      check(SqlEvidencePresentation.callTreeText(request).contains("#1 UPDATE WTPART"),
            "plain-text export includes SQL leaves even at the maximum supported stack depth");
   }

   private static void treeGrouping() throws Exception {
      List<String> stack = List.of(
            "com.ptc.dbcapture.diagnostics.SqlEvidenceCapture.observe(SqlEvidenceCapture.java:1)",
            "org.apache.logging.log4j.Logger.info(Logger.java:1)",
            "wt.pds.SQLDatabasePds.execute(SQLDatabasePds.java:10)",
            "wt.fc.StandardPersistenceManager.store(StandardPersistenceManager.java:20)",
            "example.PartForm.create(PartForm.java:30)",
            "org.apache.coyote.ajp.AjpProcessor.service(AjpProcessor.java:1)",
            "java.base/java.lang.Thread.run(Thread.java:840)");
      SqlEvidence.Event insert = event(1, "req1", "mc1", "INSERT", stack, false);
      SqlEvidence.Event update = event(2, "req1", "mc1", "UPDATE", stack, true);
      SqlEvidence.Event duplicate = event(3, "req1", "mc1", "INSERT", stack, false);
      SqlEvidence.Event otherContext = event(4, "req1", "mc2", "DELETE", stack, false);
      SqlEvidence.Event reusedThread = event(5, "req2", "mc3", "DELETE", stack, false);
      List<SqlEvidencePresentation.Request> requests = SqlEvidencePresentation.requests(
            List.of(reusedThread, otherContext, update, duplicate, insert));
      check(requests.size() == 2, "AJP thread reuse cannot merge different native requests");
      var first = requests.get(0);
      check(first.id().equals("req1") && first.statements().size() == 4,
            "requests and statements follow native sequence, not supplied list order");
      check(first.contexts().size() == 2, "method contexts within one request stay separate");
      var root = first.contexts().get(0).roots().get(0);
      check(root.frame().equals("example.PartForm.create(PartForm.java:30)"),
            "tree starts at the outer application caller");
      check(root.children().get(0).frame().startsWith("wt.fc.StandardPersistenceManager."),
            "actual persistence call path is retained");
      var nativeCall = root.children().get(0).children().get(0);
      check(nativeCall.frame().startsWith("wt.pds.SQLDatabasePds.execute"),
            "native SQL issuing frame is next to the SQL leaves");
      check(nativeCall.statements().stream().map(SqlEvidencePresentation.StatementRef::sequence)
                  .toList().equals(List.of(1L, 2L, 3L)),
            "equal paths are shared but repeated identical statements are never deduplicated");
      check(nativeCall.statements().get(0).label().equals("#1 CREATE (INSERT) WTPART"),
            "business CREATE is labelled as the actual INSERT, not CREATE TABLE");
      check(first.statements().get(0).hiddenFrames() == 4,
            "omitted plumbing frames are counted explicitly");
      check(first.statements().get(0).rawStack().equals(String.join("\n", stack)),
            "full captured stack is unchanged and available separately");
      check(first.statements().get(1).stackTruncated(), "frame-limit disclosure survives projection");
      check(first.statements().get(0).nativeMessage().equals(insert.getStatement().getNativeMessage()),
            "original native message is not overwritten by formatted SQL");
      check(first.contexts().get(0).target().isEmpty(),
            "missing native MethodContext target is not replaced with an inferred method");
      check(SqlEvidencePresentation.requests(List.of()).isEmpty(), "no synthetic evidence for an empty capture");
      var noFrames = SqlEvidencePresentation.requests(List.of(event(9, "r", "c", "DELETE",
            List.of("java.base/java.lang.Thread.run(Thread.java:840)"), true))).get(0);
      check(noFrames.contexts().get(0).roots().get(0).frame().startsWith("No application frame"),
            "no invented caller when only plumbing frames were recorded");
      check(noFrames.contexts().get(0).roots().get(0).statements().size() == 1,
            "a missing caller does not hide the actual SQL statement");
      var bytes = new java.io.ByteArrayOutputStream();
      try (var output = new java.io.ObjectOutputStream(bytes)) {
         output.writeObject(first);
      }
      try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
         check(first.equals(input.readObject()), "complete request/tree DTO survives the service serialization boundary");
      }
   }

   private static void callTreeExport() {
      List<String> stack = List.of("example.Store.save(Store.java:2)", "example.Form.submit(Form.java:1)");
      List<SqlEvidencePresentation.Request> requests = SqlEvidencePresentation.requests(List.of(
            event(1, "first-request", "context-1", "INSERT", stack, false),
            event(2, "first-request", "context-1", "UPDATE", stack, false),
            event(3, "first-request", "context-2", "DELETE", stack, false),
            event(4, "reused-thread-request", "context-3", "INSERT", stack, false)));
      String text = SqlEvidencePresentation.callTreeText(requests.get(0));
      check(text.startsWith("Servlet request: first-request\nAJP thread: ajp-nio-127.0.0.1-8010-exec-1 [42]\n"
                  + "Authenticated user: test-admin\nURI: /Windchill/ptc1/action\n"),
            "plain-text export includes the native request identity, not just the pooled AJP thread");
      check(text.contains("MethodContext context-1\n  example.Form.submit(Form.java:1)\n"
                  + "    example.Store.save(Store.java:2)\n"
                  + "      #1 CREATE (INSERT) WTPART\n      #2 UPDATE WTPART\n"),
            "plain-text export preserves caller-to-SQL order, indentation and repeated SQL leaves");
      check(text.contains("MethodContext context-2\n") && text.contains("#3 DELETE WTPART"),
            "every MethodContext belongs to the same copied request but remains independently labelled");
      check(!text.contains("reused-thread-request") && !text.contains("context-3") && !text.contains("#4"),
            "copying a request never includes another request on a reused AJP thread");
      var raw = new SqlEvidencePresentation.Request("request<&>", "ajp", "user<&>", "/literal?a=1&b=2",
            List.of(new SqlEvidencePresentation.Context("ctx<&>", "example.<target>",
                  List.of(new SqlEvidencePresentation.Node("example.<frame>&", List.of(),
                        List.of(new SqlEvidencePresentation.StatementRef(7, "DELETE", "WTPART")))))), List.of());
      String literal = SqlEvidencePresentation.callTreeText(raw);
      check(literal.contains("request<&>") && literal.contains("user<&>") && literal.contains("/literal?a=1&b=2")
                  && literal.contains("ctx<&>") && literal.contains("Target: example.<target>")
                  && literal.contains("example.<frame>&"),
            "tree export is literal text; JSP escaping protects rendering without polluting the clipboard");
      check(!literal.contains("<details>") && !literal.contains("&lt;") && !literal.contains("&amp;"),
            "tree export never copies markup or HTML entities");
      check(SqlEvidencePresentation.callTreeText(requests.get(1)).contains("#4 CREATE (INSERT) WTPART"),
            "the peer request remains separately copyable");
   }

   private static void sqlFormatting() {
      var update = SqlEvidencePresentation.sqlText(
            "Update=UPDATE WTPart SET name='a, AND O''Brien', flag=? WHERE idA2A2=?;Bind Parameters=[1, 42]");
      check(update.sql().equals("UPDATE WTPart\nSET name='a, AND O''Brien', flag=?\nWHERE idA2A2=?"),
            "UPDATE formatting changes only SQL whitespace outside literals");
      check(update.binds().equals("[1, 42]"), "only the original native bind annotation is separated");
      for (String literal : List.of("'a;Bind Parameters=x'", "q'[O'Brien, WHERE ;Bind Parameters=x]'",
            "Q'{x, AND ' ;Bind Parameters=y}'", "nq'<x, WHERE ' ;Bind Parameters=z>'",
            "q'!x ' AND ;Bind Parameters=z!'", "'<script> & </pre>'")) {
         var text = SqlEvidencePresentation.sqlText(
               "Insert Statement=INSERT INTO WTPART(name) VALUES (" + literal + ");Bind Parameters=[raw]");
         check(text.sql().contains(literal), "literal is byte-for-byte preserved: " + literal);
         check(text.binds().equals("[raw]"), "metadata-looking text inside a literal is not a split point");
      }
      var comments = SqlEvidencePresentation.sqlText(
            "Update(Batch) Statement=UPDATE \"WTPART\" /* ;Bind Parameters=comment */ SET \"WHERE\"=?"
            + " -- ;Bind Parameters=another comment\nWHERE idA2A2=?;Bind Parameters=[[1,2]]");
      check(comments.sql().contains("/* ;Bind Parameters=comment */")
                  && comments.sql().contains("-- ;Bind Parameters=another comment\n"),
            "comments are preserved and line-comment termination is not lost");
      check(comments.sql().contains("\"WHERE\""), "quoted identifier is not treated as a clause");
      check(comments.binds().equals("[[1,2]]"), "batch bind structure remains unchanged");
      var absent = SqlEvidencePresentation.sqlText("Delete=DELETE FROM WTPart WHERE idA2A2=?");
      check(absent.binds().isEmpty() && absent.sql().contains("?"), "no reconstructed binds");
      var named = SqlEvidencePresentation.sqlText("UPDATE WTPART SET name=:SET WHERE idA2A2=:WHERE");
      check(named.sql().contains("name=:SET") && named.sql().contains("idA2A2=:WHERE"),
            "formatting does not split named bind identifiers");
      var carriageReturn = SqlEvidencePresentation.sqlText(
            "UPDATE WTPART SET name=? -- comment\rWHERE idA2A2=?;Bind Parameters=[1,2]");
      check(carriageReturn.binds().equals("[1,2]") && carriageReturn.sql().contains("-- comment\r"),
            "CR-terminated native SQL comments do not swallow bind metadata");
      String longLiteral = "'" + "x, WHERE &".repeat(400) + "'";
      check(SqlEvidencePresentation.sqlText("INSERT INTO WTPART VALUES (" + longLiteral + ")")
                  .sql().contains(longLiteral), "long literals are never wrapped by changing their content");
      check(SqlEvidencePresentation.sqlText("EXECUTE: MERGE INTO WTPART a USING x ON (a.id=x.id)"
                  + " WHEN MATCHED THEN UPDATE SET name=?").sql().startsWith("MERGE INTO"),
            "existing MERGE evidence is preserved, not reclassified as CREATE");
   }

   private static void health() {
      assertTone(CaptureHealth.Tone.GREEN, "COMPLETED", "FLASHBACK", null, null);
      assertTone(CaptureHealth.Tone.YELLOW, "RUNNING", "FLASHBACK", null, null);
      assertTone(CaptureHealth.Tone.RED, "FAILED", "FLASHBACK", null, "ORA-01555 failure");
      assertTone(CaptureHealth.Tone.RED, "ABORTED", "FLASHBACK", null, null);
      assertTone(CaptureHealth.Tone.RED, "COMPLETED_WARNINGS", "FLASHBACK", null, null);
      assertTone(CaptureHealth.Tone.RED, "COMPLETED", "FLASHBACK+SNAPSHOT", null, null);
      String recovered = "Snapshot recovery (ORA-01555) for 2 table(s): WTPART, WTPARTMASTER.\n"
            + "Net changes recovered by comparing AS OF SCN 100 and 200 (inclusive capture bounds). "
            + "Intermediate changes, commit SCN/time and transaction ID are unavailable.";
      assertTone(CaptureHealth.Tone.GREEN, "COMPLETED_WARNINGS", "FLASHBACK+SNAPSHOT", recovered, null);
      String legacy = "WTPART: Flashback version history is unavailable (ORA-1555): ORA-01555: snapshot too old\n"
            + "; recovered net changes by comparing AS OF SCN 100 and 200 (inclusive capture bounds). "
            + "Intermediate changes, commit SCN/time and transaction ID are unavailable.";
      assertTone(CaptureHealth.Tone.GREEN, "COMPLETED", "FLASHBACK+SNAPSHOT", legacy, null);
      String hybrid = "Endpoint-ID history recovery (ORA-01555) for 1 table(s): "
            + "WTPARTMASTER (2 row(s)).\n"
            + "Net-changed row IDs recovered by comparing AS OF SCN 100 and 200; "
            + "ID-limited Version Query restored change SCN/time and transaction ID "
            + "for every endpoint-visible change.\n"
            + "Transient-only operations with no endpoint difference may still be unavailable.";
      assertTone(CaptureHealth.Tone.GREEN, "COMPLETED_WARNINGS", "FLASHBACK+SNAPSHOT", hybrid, null);
      for (String warning : List.of(
            "WTDOCUMENT: No changes were recorded for this table.",
            "WTPART: 5001 changed rows exceeded the 5000 row cap; only the first 5000 were recorded.",
            "WTPART: skipped - malformed data",
            "Could not reconstruct the starting monitoring scope.",
            "An unclassified warning",
            recovered + "\nWTDOCUMENT: endpoint snapshot comparison also failed.",
            legacy.substring(0, legacy.length() - 15) + "...")) {
         assertTone(CaptureHealth.Tone.RED, "COMPLETED_WARNINGS", "FLASHBACK", warning, null);
      }
      for (String warning : List.of(
            "Request SQL/stack evidence: TIMED_OUT. Window ended; SQL coverage is limited.",
            "Request SQL/stack evidence: ERROR. Observation failed.",
            "Request SQL/stack evidence: LIMIT_REACHED. over limit=1.",
            "Request SQL/stack evidence: COMPLETE. unfinished=2.")) {
         assertTone(CaptureHealth.Tone.RED, "COMPLETED_WARNINGS", "FLASHBACK", warning, null);
      }
      assertTone(CaptureHealth.Tone.YELLOW, "COMPLETED_WARNINGS", "FLASHBACK",
            "Request SQL/stack evidence: COMPLETE. unfinished=0, over limit=0, truncated stacks=2.", null);
      assertTone(CaptureHealth.Tone.YELLOW, "COMPLETED_WARNINGS", "FLASHBACK",
            "Request SQL/stack evidence is unavailable: no eligible physical tables in scope.", null);
      assertTone(CaptureHealth.Tone.GREEN, "COMPLETED_WARNINGS", "FLASHBACK",
            "Could not read USER_TAB_MODIFICATIONS at start (no view); every table will be examined at stop.", null);
      var health = CaptureHealth.assess("COMPLETED_WARNINGS", "FLASHBACK+SNAPSHOT", recovered, null);
      check(health.summary().lines().count() == 3, "warning summary has exactly cause / impact / action lines");
      check(health.summary().contains("ORA-01555") && health.summary().contains("intermediate")
                  && health.summary().contains("full diagnostics"),
            "compact warning retains diagnosis, limitation and next check");
      check(health.summary().lines().allMatch(line -> line.length() <= 110), "summary lines are bounded");
      check(CaptureHealth.assess("COMPLETED_WARNINGS", "FLASHBACK+SNAPSHOT", hybrid, null)
                  .label().equals("Recovered row history"),
            "hybrid completion has an explicit green-quality label while retaining warnings");
      check(CaptureHealth.assess("COMPLETED", "FLASHBACK", null, null).summary().isEmpty(),
            "clean captures do not display an artificial warning");
   }

   private static void assertTone(CaptureHealth.Tone expected, String status, String mode,
                                  String warning, String error) {
      var actual = CaptureHealth.assess(status, mode, warning, error);
      check(actual.tone() == expected, "health " + status + " expected " + expected + " got " + actual);
   }

   private static SqlEvidence.Event event(long sequence, String request, String context, String operation,
                                           List<String> stack, boolean truncated) {
      return new SqlEvidence.Event(sequence, 1000 + sequence, 42, "ajp-nio-127.0.0.1-8010-exec-1",
            request, context, "test-admin", "test-admin", "/Windchill/ptc1/action", null, null,
            new SqlEvidence.Statement(operation, "WTPART", switch (operation) {
               case "INSERT" -> "Insert Statement=INSERT INTO WTPART(name) VALUES (?)";
               case "UPDATE" -> "Update=UPDATE WTPART SET name=? WHERE idA2A2=?";
               default -> "Delete=DELETE FROM WTPART WHERE idA2A2=?";
            }), stack, truncated);
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
      checks++;
   }
}
