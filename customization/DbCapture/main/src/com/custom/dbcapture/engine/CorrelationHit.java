package com.custom.dbcapture.engine;

/** What the log tables could say about the cause of one change. */
public final class CorrelationHit {

   private final String actionName;
   private final String requestUri;
   private final String user;
   private final String stackTrace;

   CorrelationHit(String actionName, String requestUri, String user, String stackTrace) {
      this.actionName = actionName;
      this.requestUri = requestUri;
      this.user = user;
      this.stackTrace = stackTrace;
   }

   public String getActionName() {
      return actionName;
   }

   public String getRequestUri() {
      return requestUri;
   }

   public String getUser() {
      return user;
   }

   public String getStackTrace() {
      return stackTrace;
   }
}
