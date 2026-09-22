package com.custom.dbcapture;

import wt.util.WTException;

/**
 * Starts and stops DB capture sessions.
 *
 * Monitoring is server-wide: at most one session runs at a time, and it records
 * everything the datastore did in the window regardless of which user caused
 * it. That matches how the command line DBDiff2 was used - take a reading,
 * perform the operation under investigation, take another reading.
 */
public interface DbCaptureService {

   /**
    * Opens a capture window.
    *
    * @param label free text describing what is about to be investigated
    * @return the new session, in {@link DbCaptureSession#STATUS_RUNNING}
    * @throws WTException if a session is already running
    */
   DbCaptureSession startCapture(String label) throws WTException;

   /**
    * Closes the running window, collects what changed, and stores the result.
    *
    * @return the completed session
    * @throws WTException if no session is running
    */
   DbCaptureSession stopCapture() throws WTException;

   /** The running session, or null when nothing is being captured. */
   DbCaptureSession getActiveCapture() throws WTException;

   /**
    * Renames a capture. The description is the only field a reader edits - it
    * is what turns "CAP-000007" into "checkout of part 0000000021".
    */
   DbCaptureSession setDescription(String captureId, String description)
         throws WTException;

   DbCaptureSession setDescriptionByOid(String sessionOid, String description)
         throws WTException;

   void deleteCaptureByOid(String sessionOid) throws WTException;

   /** Minimal state for Quick Links controls and the all-user running banner. */
   DbCaptureBannerState getBannerState() throws WTException;

   DbCaptureObjectReader.Snapshot inspectObject(String capturedEntryId) throws WTException;

   DbCaptureDiagnostics.Report readDiagnostics(String capturedSessionId) throws WTException;
}
