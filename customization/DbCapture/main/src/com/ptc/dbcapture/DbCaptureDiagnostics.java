package com.ptc.dbcapture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.ptc.dbcapture.diagnostics.SessionEvidenceStore;
import com.ptc.dbcapture.diagnostics.SqlEvidence;
import com.ptc.dbcapture.diagnostics.SqlEvidenceCapture;
import com.ptc.dbcapture.diagnostics.SqlEvidencePresentation;
import com.ptc.dbcapture.diagnostics.SqlStatementClassifier;
import com.ptc.dbcapture.diagnostics.CaptureHealth;

import wt.fc.ObjectIdentifier;
import wt.fc.PersistenceHelper;
import wt.util.WTException;
import wt.util.WTProperties;

/** Authorized application boundary for private, capture-specific request evidence. */
public final class DbCaptureDiagnostics {
   public record Report(String captureId, String state, String reason, String node, long startedMillis,
                        long finishedMillis, long observed, long filtered, long rejected, long failed,
                        long unfinished, long discarded, int recorded, List<String> tables,
                        List<SqlEvidencePresentation.Request> requests, String captureStatus, String mode,
                        String warnings, String error, CaptureHealth.Assessment health,
                        List<String> notRecordedTables) implements java.io.Serializable {
      private static final long serialVersionUID = 0L;

      public Report {
         tables = List.copyOf(tables);
         notRecordedTables = notRecordedTables == null ? null : List.copyOf(notRecordedTables);
      }

      public Report(String captureId, String state, String reason, String node, long startedMillis,
                    long finishedMillis, long observed, long filtered, long rejected, long failed,
                    long unfinished, long discarded, int recorded, List<String> tables,
                    List<SqlEvidencePresentation.Request> requests, String captureStatus, String mode,
                    String warnings, String error, CaptureHealth.Assessment health) {
         this(captureId, state, reason, node, startedMillis, finishedMillis, observed, filtered, rejected,
               failed, unfinished, discarded, recorded, tables, requests, captureStatus, mode, warnings,
               error, health, null);
      }

      public boolean notRecordedScopeAvailable() { return notRecordedTables != null; }

      @Override
      public List<String> notRecordedTables() {
         return notRecordedTables == null ? List.of() : notRecordedTables;
      }
   }

   private DbCaptureDiagnostics() {
   }

   public static Path root() throws IOException {
      return Path.of(WTProperties.getLocalProperties().getProperty("wt.home"),
            ".dbcapture-evidence");
   }

   public static String oid(DbCaptureSession capture) {
      ObjectIdentifier oid = PersistenceHelper.getObjectIdentifier(capture);
      if (oid == null || oid.getId() <= 0) throw new IllegalArgumentException("Capture must be persisted.");
      return DbCaptureSession.class.getName() + ":" + oid.getId();
   }

   public static SqlEvidenceCapture begin(DbCaptureSession capture, List<String> tables) throws IOException {
      return begin(capture, tables, null);
   }

   public static SqlEvidenceCapture begin(DbCaptureSession capture, List<String> tables,
                                         List<String> tableCatalog) throws IOException {
      List<String> selected = tables.stream().filter(table -> !SqlStatementClassifier.isDiagnosticTable(table))
            .sorted().toList();
      return SqlEvidenceCapture.open(root(), oid(capture), selected, tableCatalog,
            SqlEvidence.Limits.forDuration(600000L));
   }

   public static Report read(String id) throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      long key = DbCaptureObjectReader.positiveId(id);
      DbCaptureSession capture = (DbCaptureSession) PersistenceHelper.manager.refresh(
            ObjectIdentifier.newObjectIdentifier(DbCaptureSession.class, key));
      if (capture == null) throw new WTException("The capture session no longer exists.");
      try (var ignored = SqlEvidenceCapture.suppress()) {
         SqlEvidence.Snapshot snapshot = new SessionEvidenceStore(root()).read(oid(capture));
         return new Report(capture.getCaptureId(), String.valueOf(snapshot.getState()),
               snapshot.getReason(), snapshot.getNode(), snapshot.getStartedMillis(), snapshot.getFinishedMillis(),
               snapshot.getObservedMessages(), snapshot.getFilteredStatements(), snapshot.getRejectedContexts(),
               snapshot.getFailedRequestStatements(), snapshot.getUnfinishedRequestStatements(),
               snapshot.getLimitDiscardedStatements(), snapshot.getEvents().size(), snapshot.getTables(),
               SqlEvidencePresentation.requests(snapshot.getEvents()), capture.getStatus(), capture.getCaptureMode(),
               capture.getWarnings(), capture.getErrorText(), CaptureHealth.assess(capture.getStatus(),
                     capture.getCaptureMode(), capture.getWarnings(), capture.getErrorText()),
               snapshot.isNotRecordedScopeAvailable() ? snapshot.getNotRecordedTables() : null);
      } catch (IOException e) {
         throw new WTException(e, "Could not read private capture diagnostics: " + e.getMessage());
      }
   }

   public static String warning(DbCaptureSession capture) throws IOException {
      SqlEvidence.Snapshot snapshot = new SessionEvidenceStore(root()).read(oid(capture));
      String state = String.valueOf(snapshot.getState());
      long truncatedStacks = snapshot.getEvents().stream().filter(SqlEvidence.Event::isStackTruncated).count();
      if ("COMPLETE".equals(state) && snapshot.getFailedRequestStatements() == 0
            && snapshot.getUnfinishedRequestStatements() == 0 && snapshot.getLimitDiscardedStatements() == 0
            && truncatedStacks == 0) {
         return null;
      }
      return "Request SQL/stack evidence: " + state + ". " + snapshot.getReason()
            + " Withheld failed-request statements=" + snapshot.getFailedRequestStatements()
            + ", unfinished=" + snapshot.getUnfinishedRequestStatements()
            + ", over limit=" + snapshot.getLimitDiscardedStatements()
            + ", truncated stacks=" + truncatedStacks + ".";
   }

   public static void delete(DbCaptureSession capture) throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      try {
         new SessionEvidenceStore(root()).delete(oid(capture));
      } catch (IOException e) {
         throw new WTException(e, "Could not delete private capture diagnostics.");
      }
   }
}
