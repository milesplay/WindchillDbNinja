package com.ptc.dbcapture;

import wt.org.OrganizationServicesHelper;
import wt.org.WTGroup;
import wt.org.WTPrincipal;
import wt.session.SessionHelper;
import wt.util.WTException;

/**
 * Restricts DB capture to site administrators.
 *
 * The action definitions already hide the buttons and the utility from everyone
 * else, but hiding a button is presentation, not a control. This check runs
 * inside the service, so it holds no matter how the call arrived - a stale
 * bookmark, a hand-built request, or a page that was open before someone's
 * membership changed.
 *
 * It matters here more than in an ordinary feature: a capture reads every
 * table in the schema and stores column values, so the results can contain
 * data the reader would not otherwise be allowed to see.
 */
public final class DbCaptureAuthorization {

   /** The site administrators group, by its well known name. */
   private static final String[] ADMIN_GROUPS = { "Administrators", "Site Administrators" };

   private DbCaptureAuthorization() {
   }

   public static void requireAdministrator() throws WTException {
      WTPrincipal principal = SessionHelper.getPrincipal();
      if (principal == null) {
         throw new WTException("DB Capture requires an authenticated session.");
      }
      if (!isAdministrator(principal)) {
         throw new WTException("DB Capture is restricted to site administrators; "
               + principal.getName() + " is not a member.");
      }
   }

   public static boolean isCurrentAdministrator() throws WTException {
      WTPrincipal principal = SessionHelper.getPrincipal();
      return principal != null && isAdministrator(principal);
   }

   public static String currentUserName() throws WTException {
      WTPrincipal principal = SessionHelper.getPrincipal();
      if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
         throw new WTException("DB Capture requires an authenticated session.");
      }
      return principal.getName();
   }

   public static void requireOwner(DbCaptureSession session) throws WTException {
      String user = currentUserName();
      String owner = session == null ? null : session.getStartedBy();
      if (!isOwner(session, user)) {
         throw new WTException("DB capture "
               + (session == null ? "" : session.getCaptureId() + " ")
               + "was started by " + (owner == null ? "an unknown user" : owner)
               + ". Only that user may stop it; wait for completion.");
      }
   }

   public static boolean isOwner(DbCaptureSession session, String userName) {
      return session != null && session.getStartedBy() != null
            && userName != null && session.getStartedBy().equals(userName);
   }

   private static boolean isAdministrator(WTPrincipal principal) throws WTException {
      for (String groupName : ADMIN_GROUPS) {
         WTGroup group = OrganizationServicesHelper.manager.getGroup(groupName);
         if (group != null
               && OrganizationServicesHelper.manager.isMember(group, principal)) {
            return true;
         }
      }
      return false;
   }
}
