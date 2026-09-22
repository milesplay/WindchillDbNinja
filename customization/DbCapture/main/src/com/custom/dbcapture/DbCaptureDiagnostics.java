package com.custom.dbcapture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.custom.dbcapture.diagnostics.SessionEvidenceStore;
import com.custom.dbcapture.diagnostics.SqlEvidence;
import com.custom.dbcapture.diagnostics.SqlEvidenceCapture;
import com.custom.dbcapture.diagnostics.SqlEvidencePresentation;
import com.custom.dbcapture.diagnostics.SqlStatementClassifier;
import com.custom.dbcapture.diagnostics.CaptureHealth;

import wt.fc.ObjectIdentifier;
import wt.fc.PersistenceHelper;
import wt.util.WTException;
import wt.util.WTProperties;

/** Authorized application boundary for private, capture-specific request evidence. */
public final class DbCaptureDiagnostics {
   public static final class Report implements java.io.Serializable {
      private static final long serialVersionUID = 0L;
      private final String captureId;
      private final String state;
      private final String reason;
      private final String node;
      private final long startedMillis;
      private final long finishedMillis;
      private final long observed;
      private final long filtered;
      private final long rejected;
      private final long failed;
      private final long unfinished;
      private final long discarded;
      private final int recorded;
      private final List<String> tables;
      private final List<SqlEvidencePresentation.Request> requests;
      private final String captureStatus;
      private final String mode;
      private final String warnings;
      private final String error;
      private final CaptureHealth.Assessment health;
      private final List<String> notRecordedTables;

      public Report(String captureId, String state, String reason, String node, long startedMillis,
                    long finishedMillis, long observed, long filtered, long rejected, long failed,
                    long unfinished, long discarded, int recorded, List<String> tables,
                    List<SqlEvidencePresentation.Request> requests, String captureStatus, String mode,
                    String warnings, String error, CaptureHealth.Assessment health,
                    List<String> notRecordedTables) {
         this.captureId = captureId;
         this.state = state;
         this.reason = reason;
         this.node = node;
         this.startedMillis = startedMillis;
         this.finishedMillis = finishedMillis;
         this.observed = observed;
         this.filtered = filtered;
         this.rejected = rejected;
         this.failed = failed;
         this.unfinished = unfinished;
         this.discarded = discarded;
         this.recorded = recorded;
         this.tables = List.copyOf(tables);
         this.requests = requests;
         this.captureStatus = captureStatus;
         this.mode = mode;
         this.warnings = warnings;
         this.error = error;
         this.health = health;
         this.notRecordedTables = notRecordedTables == null ? null : List.copyOf(notRecordedTables);
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

            public String captureId() { return captureId; }
            public String state() { return state; }
            public String reason() { return reason; }
            public String node() { return node; }
            public long startedMillis() { return startedMillis; }
            public long finishedMillis() { return finishedMillis; }
            public long observed() { return observed; }
            public long filtered() { return filtered; }
            public long rejected() { return rejected; }
            public long failed() { return failed; }
            public long unfinished() { return unfinished; }
            public long discarded() { return discarded; }
            public int recorded() { return recorded; }
            public List<String> tables() { return tables; }
            public List<SqlEvidencePresentation.Request> requests() { return requests; }
            public String captureStatus() { return captureStatus; }
            public String mode() { return mode; }
            public String warnings() { return warnings; }
            public String error() { return error; }
            public CaptureHealth.Assessment health() { return health; }

      public boolean notRecordedScopeAvailable() { return notRecordedTables != null; }

      public List<String> notRecordedTables() {
         return notRecordedTables == null ? List.of() : notRecordedTables;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) return true;
         if (!(other instanceof Report)) return false;
         Report that = (Report) other;
         return startedMillis == that.startedMillis && finishedMillis == that.finishedMillis
               && observed == that.observed && filtered == that.filtered && rejected == that.rejected
               && failed == that.failed && unfinished == that.unfinished && discarded == that.discarded
               && recorded == that.recorded && Objects.equals(captureId, that.captureId)
               && Objects.equals(state, that.state) && Objects.equals(reason, that.reason)
               && Objects.equals(node, that.node) && Objects.equals(tables, that.tables)
               && Objects.equals(requests, that.requests) && Objects.equals(captureStatus, that.captureStatus)
               && Objects.equals(mode, that.mode) && Objects.equals(warnings, that.warnings)
               && Objects.equals(error, that.error) && Objects.equals(health, that.health)
               && Objects.equals(notRecordedTables, that.notRecordedTables);
      }

      @Override
      public int hashCode() {
         return Objects.hash(captureId, state, reason, node, startedMillis, finishedMillis, observed,
               filtered, rejected, failed, unfinished, discarded, recorded, tables, requests,
               captureStatus, mode, warnings, error, health, notRecordedTables);
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
         .sorted().collect(Collectors.toUnmodifiableList());
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
