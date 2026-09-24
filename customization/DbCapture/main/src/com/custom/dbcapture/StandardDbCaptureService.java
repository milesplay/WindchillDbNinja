package com.custom.dbcapture;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.io.IOException;
import java.util.List;

import com.custom.dbcapture.engine.CaptureEngine;
import com.custom.dbcapture.engine.CaptureResult;
import com.custom.dbcapture.engine.CapturedChange;
import com.custom.dbcapture.engine.CaptureConcurrency;
import com.custom.dbcapture.engine.MonitoringScope;
import com.custom.dbcapture.engine.SqlLogWindow;
import com.custom.dbcapture.diagnostics.SqlEvidenceCapture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import wt.services.ManagerException;
import wt.services.StandardManager;
import wt.session.SessionHelper;
import wt.util.WTException;
import wt.util.WTProperties;
import wt.util.WTPropertyVetoException;

/**
 * Standard implementation of {@link DbCaptureService}.
 *
 * The running session lives in the datastore rather than in a field, so the
 * "one session at a time" rule survives a method server restart and holds
 * across a cluster. The only in-memory state is the baseline reading, and the
 * collection step is written so that losing it degrades the result rather than
 * breaking it.
 *
 * Generated setters throw {@link WTPropertyVetoException}, which is unrelated
 * to {@link WTException}; it is converted here so callers see one exception
 * type.
 */
public class StandardDbCaptureService extends StandardManager implements DbCaptureService {

   /**
    * Failures here reach the user as a dialog from the JSON endpoint. They must
    * also reach the log: a message in a browser is gone as soon as it is
    * dismissed, and the first failure of a capture is exactly when someone
    * goes looking in MethodServer-*.log.
    */
   private static final Logger LOG =
         LogManager.getLogger(StandardDbCaptureService.class.getName());

   private static final String CORRELATE_PROPERTY = "com.custom.dbcapture.correlateLogs";
   private static final int TEXT_LIMIT = 4000;

   /** Baseline for the window opened on this method server, if any. */
   private CaptureEngine.CaptureBaseline baseline;
   private final SqlLogWindow logWindow = new SqlLogWindow();
   private SqlEvidenceCapture requestEvidence;
   private String requestEvidenceIssue;

   public static StandardDbCaptureService newStandardDbCaptureService()
         throws WTException {
      StandardDbCaptureService instance = new StandardDbCaptureService();
      instance.initialize();
      return instance;
   }

   @Override
   protected synchronized void performStartupProcess() throws ManagerException {
      LOG.info("DB Capture startup leaves capture records and SQL logger levels "
            + "unchanged. A running capture remains owned by its startedBy user "
            + "across browsers; a stale RUNNING record requires explicit datastore "
            + "recovery after confirming that collection is no longer active.");
   }

   public synchronized DbCaptureSession startCapture(String label) throws WTException {
      try {
         return doStart(label);
      } catch (WTPropertyVetoException e) {
         throw new WTException(e, "Could not initialise the capture session.");
      }
   }

   private DbCaptureSession doStart(String label)
         throws WTException, WTPropertyVetoException {

      DbCaptureAuthorization.requireAdministrator();

      Connection c = DbCaptureJdbc.connection();
      try {
         CaptureConcurrency.lockForStart(c);
      } catch (SQLException e) {
         throw new WTException(e, e.getMessage());
      }
      DbCaptureSession running = DbCaptureHelper.findRunningSession();
      if (running != null) {
         // The menu that offered Start may have been showing cached state, so
         // say plainly what is running and how to get out of it rather than
         // just refusing.
         throw new WTException("A DB capture is already running: "
               + running.getCaptureId()
               + (running.getStartedBy() == null ? ""
                  : ", started by " + running.getStartedBy())
               + (running.getStartTime() == null ? ""
                  : " at " + running.getStartTime())
               + ". Only " + (running.getStartedBy() == null
                  ? "the starting user" : running.getStartedBy())
               + " may use Ninja Stealth to stop the capture. Wait for completion. If the menu "
               + "still offers Start, reload the page "
               + "first - the Quick Links menu is cached per page load.");
      }

      CaptureEngine engine = new CaptureEngine(c);
      try {
         this.baseline = engine.begin();
      } catch (SQLException e) {
         LOG.error("DB Capture could not read the starting database state.", e);
         throw new WTException(e, "Could not read the starting database state: "
               + e.getMessage());
      }

      if (isCorrelationEnabled()) {
         logWindow.open();
      }

      DbCaptureSession session = DbCaptureSession.newDbCaptureSession();
      session.setCaptureId(DbCaptureHelper.nextCaptureId());
      LOG.info("DB Capture " + session.getCaptureId() + " starting at SCN "
            + baseline.getScn() + ".");
      session.setDescription(clip(label, 400));
      session.setStatus(DbCaptureSession.STATUS_RUNNING);
      session.setCaptureMode(DbCaptureSession.MODE_FLASHBACK);
      session.setStartedBy(currentUser());
      session.setStartTime(now());
      session.setStartScn(baseline.getScn());
      session = DbCaptureHelper.save(session);
      requestEvidenceIssue = null;
      try {
         MonitoringScope.Catalog catalog = baseline.getCatalog();
         requestEvidence = DbCaptureDiagnostics.begin(session,
               catalog.includedTables(baseline.getScope()), catalog.getTableNames());
         if (requestEvidence == null) {
            requestEvidenceIssue = "Request SQL/stack evidence is unavailable: no eligible physical tables "
                  + "are included in this capture's monitoring scope.";
         } else if (!requestEvidence.isActive()) {
            requestEvidenceIssue = DbCaptureDiagnostics.warning(session);
         }
      } catch (IOException | IllegalArgumentException | IllegalStateException e) {
         requestEvidence = null;
         requestEvidenceIssue = "Request SQL/stack capture could not start: " + e.getMessage();
         LOG.error("DB Capture request diagnostics could not start for " + session.getCaptureId(), e);
      }
      if (requestEvidenceIssue != null) {
         LOG.warn("DB Capture {}: {}", session.getCaptureId(), requestEvidenceIssue);
         session.setWarnings(clip(requestEvidenceIssue, TEXT_LIMIT));
         session = DbCaptureHelper.save(session);
      }
      return session;
   }

   public synchronized DbCaptureSession stopCapture() throws WTException {
      try {
         return doStop();
      } catch (WTPropertyVetoException e) {
         throw new WTException(e, "Could not record the capture result.");
      }
   }

   private DbCaptureSession doStop() throws WTException, WTPropertyVetoException {
      DbCaptureAuthorization.requireAdministrator();

      Connection c = DbCaptureJdbc.connection();
      Long runningId;
      try {
         runningId = CaptureConcurrency.lockRunningSession(c);
      } catch (SQLException e) {
         throw new WTException(e, e.getMessage());
      }
      DbCaptureSession session = runningId == null ? null : DbCaptureHelper.findSession(runningId.longValue());
      if (session == null) {
         throw new WTException("No DB capture is running. If the menu offered "
               + "Stop, reload the page - the Quick Links menu is cached per "
               + "page load.");
      }
      DbCaptureAuthorization.requireOwner(session);

      Timestamp endTime = now();
      try {
         logWindow.close();
         String traceWarning = finishRequestEvidence(session, false);
         CaptureEngine.CaptureBaseline effective = baselineFor(session);
         CaptureEngine engine = new CaptureEngine(c, effective.getScope());
         CaptureResult result = engine.end(effective, session.getStartTime(), endTime,
                                           isCorrelationEnabled());
         session.setEndTime(endTime);
         session.setEndScn(result.getEndScn());
         session.setTablesChanged(result.getTablesChanged());
         session.setWarnings(result.warningText(TEXT_LIMIT));
         if (traceWarning != null) {
            session.setWarnings(clip((session.getWarnings() == null ? "" : session.getWarnings() + "\n")
                  + traceWarning, TEXT_LIMIT));
         }
         if (result.hasSnapshotFallback()) {
            session.setCaptureMode(DbCaptureSession.MODE_MIXED);
         }
         for (String warning : result.getWarnings()) {
            LOG.warn("DB Capture {}: {}", session.getCaptureId(), warning);
         }
         applyCounts(session, result.getChanges());
         DbCaptureHelper.storeChanges(session, result.getChanges());
         session.setStatus(result.getWarnings().isEmpty() && traceWarning == null
               ? DbCaptureSession.STATUS_COMPLETED : DbCaptureSession.STATUS_COMPLETED_WARNINGS);
         session = DbCaptureHelper.save(session);
      } catch (SQLException | WTException | WTPropertyVetoException | RuntimeException e) {
         LOG.error("DB Capture " + session.getCaptureId()
               + " failed while collecting or storing changes.", e);
         try {
            session.setEndTime(endTime);
            session.setStatus(DbCaptureSession.STATUS_FAILED);
            session.setErrorText(clip(e.getMessage(), TEXT_LIMIT));
            DbCaptureHelper.save(session);
         } catch (WTException | WTPropertyVetoException | RuntimeException failure) {
            e.addSuppressed(failure);
            LOG.error("DB Capture " + session.getCaptureId()
                  + " could not persist its FAILED status.", failure);
         }
         throw new WTException(e, "DB capture failed while collecting or storing changes: "
               + e.getMessage());
      } finally {
         this.baseline = null;
      }
      return session;
   }

   public DbCaptureSession getActiveCapture() throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      return DbCaptureHelper.findRunningSession();
   }

   public DbCaptureSession setDescription(String captureId, String description)
         throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      DbCaptureSession session = DbCaptureHelper.findSession(captureId);
      if (session == null) {
         throw new WTException("No capture session with id " + captureId + ".");
      }
      return setDescription(session, description);
   }

   public DbCaptureSession setDescriptionByOid(String sessionOid, String description)
         throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      DbCaptureSession session = DbCaptureHelper.findSession(parseSessionOid(sessionOid));
      if (session == null) {
         throw new WTException("The selected capture session no longer exists.");
      }
      return setDescription(session, description);
   }

   public void deleteCaptureByOid(String sessionOid) throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      DbCaptureSession session = DbCaptureHelper.findSession(parseSessionOid(sessionOid));
      if (session == null) {
         throw new WTException("The selected capture session no longer exists.");
      }
      DbCaptureHelper.deleteSession(session);
   }

   public DbCaptureBannerState getBannerState() throws WTException {
      String currentUser = DbCaptureAuthorization.currentUserName();
      DbCaptureSession active = DbCaptureHelper.findRunningSession();
      boolean administrator = DbCaptureAuthorization.isCurrentAdministrator();
      boolean owner = administrator && active != null && currentUser.equals(active.getStartedBy());
      return new DbCaptureBannerState(active != null,
         !administrator || active == null ? null : active.getCaptureId(),
         !administrator || active == null ? null : active.getStartedBy(),
         !administrator || active == null || active.getStartTime() == null
            ? 0L : active.getStartTime().getTime(),
         administrator, owner);
   }

   private static DbCaptureSession setDescription(DbCaptureSession session, String description)
         throws WTException {
      try {
         session.setDescription(clip(description, 400));
         return DbCaptureHelper.save(session);
      } catch (WTPropertyVetoException e) {
         throw new WTException(e, "Could not update the description.");
      }
   }

   private static long parseSessionOid(String value) throws WTException {
      if (value == null || !value.matches("[0-9]+")) {
         throw new WTException("Invalid capture session identifier.");
      }
      try {
         long parsed = Long.parseLong(value);
         if (parsed <= 0) throw new NumberFormatException("not positive");
         return parsed;
      } catch (NumberFormatException e) {
         throw new WTException(e, "Invalid capture session identifier.");
      }
   }

   public DbCaptureObjectReader.Snapshot inspectObject(String capturedEntryId) throws WTException {
      try (var ignored = SqlEvidenceCapture.suppress()) {
         return DbCaptureObjectReader.read(capturedEntryId);
      }
   }

   public DbCaptureDiagnostics.Report readDiagnostics(String capturedSessionId) throws WTException {
      return DbCaptureDiagnostics.read(capturedSessionId);
   }

   private String finishRequestEvidence(DbCaptureSession session, boolean abort) {
      try {
         if (requestEvidence != null) {
            if (abort) requestEvidence.abort(); else requestEvidence.stop();
         }
         return requestEvidenceIssue != null ? requestEvidenceIssue : DbCaptureDiagnostics.warning(session);
      } catch (IOException | IllegalStateException e) {
         LOG.error("DB Capture request diagnostics could not finalize for " + session.getCaptureId(), e);
         return "Request SQL/stack evidence could not be finalized: " + e.getMessage();
      } finally {
         requestEvidence = null;
         requestEvidenceIssue = null;
      }
   }

   /**
    * The baseline to collect against.
    *
    * Normally this is the reading taken at start. When the stop lands on a
    * different method server that reading is gone, but the SCN was persisted
    * on the session. Without the original catalog/counters the engine examines
    * the current eligible scope, explicitly disclosing that it is not frozen.
    */
   private CaptureEngine.CaptureBaseline baselineFor(DbCaptureSession session) {
      if (baseline != null && baseline.getScn() == session.getStartScn()) {
         return baseline;
      }
      return CaptureEngine.baselineFromScn(session.getStartScn(),
            "The original in-memory monitoring scope, physical catalog and activity "
          + "are unavailable (for example after a restart or on another method server); "
          + "every table in the current eligible scope is examined instead.");
   }

   private static void applyCounts(DbCaptureSession session, List<CapturedChange> changes)
         throws WTPropertyVetoException {
      int created = 0;
      int updated = 0;
      int deleted = 0;
      int logicallyDeleted = 0;
      for (CapturedChange change : changes) {
         String op = change.getOperation();
         if (DbCaptureChange.OP_CREATE.equals(op)) {
            created++;
         } else if (DbCaptureChange.OP_DELETE.equals(op)) {
            deleted++;
         } else if (DbCaptureChange.OP_LOGICAL_DELETE.equals(op)) {
            logicallyDeleted++;
         } else {
            updated++;
         }
      }
      session.setCreatedCount(created);
      session.setUpdatedCount(updated);
      session.setDeletedCount(deleted);
      session.setLogicalDeletedCount(logicallyDeleted);
   }

   private static boolean isCorrelationEnabled() {
      try {
         String raw = WTProperties.getLocalProperties()
               .getProperty(CORRELATE_PROPERTY);
         return raw != null && "true".equalsIgnoreCase(raw.trim());
      } catch (Exception e) {
         LOG.warn("DB Capture legacy SQL logging remains disabled: its opt-in setting could not be read.", e);
         return false;
      }
   }

   private static String currentUser() {
      try {
         return SessionHelper.manager.getPrincipal().getName();
      } catch (Exception e) {
         return null;
      }
   }

   private static Timestamp now() {
      return new Timestamp(System.currentTimeMillis());
   }

   private static String clip(String s, int limit) {
      if (s == null) {
         return null;
      }
      return s.length() > limit ? s.substring(0, limit - 3) + "..." : s;
   }
}
