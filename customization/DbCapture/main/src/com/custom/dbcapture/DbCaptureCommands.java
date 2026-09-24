package com.custom.dbcapture;

import java.util.ArrayList;

import com.ptc.core.components.forms.FormProcessingStatus;
import com.ptc.core.components.forms.FormResult;
import com.ptc.core.components.forms.FormResultAction;
import com.ptc.core.components.util.FeedbackMessage;
import com.ptc.core.ui.resources.FeedbackType;
import com.ptc.netmarkets.util.beans.NmCommandBean;

import wt.method.RemoteAccess;
import wt.session.SessionHelper;
import wt.util.WTException;

/**
 * Entry points behind the header buttons and the table row actions.
 *
 * Routing the buttons through Windchill actions rather than a bare servlet
 * means authentication, the URL validators and the UI component access checks
 * all apply, which is what keeps a diagnostic tool from becoming a way to poke
 * at the datastore.
 */
public class DbCaptureCommands implements RemoteAccess {

   /** Quick Links entry: opens a capture window. */
   public static FormResult startDbCapture(NmCommandBean commandBean) {
      try {
         String label = commandBean.getTextParameter("dbcLabel");
         DbCaptureSession session = DbCaptureHelper.service.startCapture(label);
         return success("DB capture " + session.getCaptureId() + " started."
               + (session.getWarnings() == null ? "" : " " + session.getWarnings()));
      } catch (Exception e) {
         return failure(e);
      }
   }

   /** Header button: closes the window and collects what changed. */
   public static FormResult stopDbCapture(NmCommandBean commandBean) {
      try {
         DbCaptureSession session = DbCaptureHelper.service.stopCapture();
         FormResult result = withFeedback(FormProcessingStatus.SUCCESS,
               FeedbackType.SUCCESS, DbCaptureHelper.completionMessage(session));
         result.setNextAction(FormResultAction.JAVASCRIPT);
         result.setJavascript(DbCaptureNavigation.redirectScript(
               DbCaptureNavigation.resultsUrl(session.getCaptureId())));
         return result;
      } catch (Exception e) {
         return failure(e);
      }
   }

   /** Row action: removes a session together with everything it recorded. */
   public static FormResult deleteDbCaptureSession(NmCommandBean commandBean) {
      try {
         DbCaptureAuthorization.requireAdministrator();
         ArrayList selected = commandBean.getSelected();
         if (selected == null || selected.isEmpty()) {
            return failure(new WTException("No capture session was selected."));
         }
         int removed = 0;
         for (Object entry : selected) {
            Object target = NmCommandBean.getOidFromObject(entry).getRefObject();
            if (target instanceof DbCaptureSession) {
               DbCaptureHelper.deleteSession((DbCaptureSession) target);
               removed++;
            }
         }
         return rowSuccess(removed + " capture session(s) deleted.");
      } catch (Exception e) {
         return failure(e);
      }
   }

   /**
    * Outcome that only needs the table it acted on refreshed.
    *
    * REFRESH_OPENER was not enough. The results page deliberately returns no
    * rows unless the request carries dbcSearched, so a plain framework refresh
    * reloaded the table into an empty state rather than an updated one - which
    * looked like the delete had not been picked up and left the old rows on
    * screen until the page was reloaded by hand. The page therefore exposes
    * dbCaptureReloadAfterDelete, which re-runs the current search (keyword and
    * capture filter included) and drops the filter if the capture it pointed at
    * has just been deleted.
    *
    * The action's script can run inside a frame, so walk out to whichever
    * window actually owns the page before giving up.
    */
   private static FormResult rowSuccess(String message) {
      FormResult result = withFeedback(FormProcessingStatus.SUCCESS,
                                       FeedbackType.SUCCESS, message);
      result.setNextAction(FormResultAction.JAVASCRIPT);
      result.setJavascript(
            "(function(){"
          + " var w = [window, window.parent, window.top, window.opener];"
          + " for (var i = 0; i < w.length; i++) {"
          + "   try {"
          + "     if (w[i] && typeof w[i].dbCaptureReloadAfterDelete === 'function') {"
          + "       w[i].dbCaptureReloadAfterDelete();"
          + "       return;"
          + "     }"
          + "   } catch (e) { /* cross-frame access - try the next one */ }"
          + " }"
          + "})();");
      return result;
   }

   private static FormResult success(String message) {
      FormResult result = withFeedback(FormProcessingStatus.SUCCESS,
                                       FeedbackType.SUCCESS, message);
      // Reload the whole shell, not just the content area.
      //
      // The Quick Links menu comes from a DynamicMenuButton configured with
      // loadOnce:true, so it is fetched once per shell load and cached on the
      // client. The enabled/disabled states of Start, Stop and Abort are
      // decided when that fetch happens, so after starting a capture the menu
      // would keep showing Start available and Stop greyed out. Refreshing only
      // the content area does not rebuild the shell, so the menu would stay
      // stale; a top-level reload is what actually re-evaluates it.
      // Reload the whole shell, but not before the ribbon has been read.
      //
      // The Quick Links menu comes from a DynamicMenuButton with loadOnce:true,
      // so it is fetched once per shell load and cached; the enabled state of
      // Start and Stop is decided at that fetch and never re-evaluated. Only a
      // top-level reload rebuilds it - refreshing the content area leaves the
      // shell, and therefore the menu, untouched. Reloading immediately also
      // discarded the feedback message, which is why Stop appeared to report
      // nothing, so the reload is deferred a few seconds.
      result.setNextAction(FormResultAction.JAVASCRIPT);
      result.setJavascript(
            "setTimeout(function(){ try { (window.top || window).location.reload(); }"
          + " catch (e) { window.location.reload(); } }, 4000);");
      return result;
   }

   private static FormResult failure(Exception e) {
      String message = e instanceof WTException
            ? ((WTException) e).getLocalizedMessage()
            : String.valueOf(e.getMessage());
      return withFeedback(FormProcessingStatus.FAILURE, FeedbackType.FAILURE, message);
   }

   /**
    * Attaches the outcome as a feedback message so the shell shows it in the
    * usual banner. A message that cannot be built is not worth failing over -
    * the action itself has already run.
    */
   private static FormResult withFeedback(FormProcessingStatus status,
                                          FeedbackType type, String message) {
      FormResult result = new FormResult(status);
      try {
         result.addFeedbackMessage(new FeedbackMessage(
               type, SessionHelper.getLocale(), message, null, new String[0]));
      } catch (Exception ignored) {
         // Status alone still tells the caller what happened.
      }
      return result;
   }
}
