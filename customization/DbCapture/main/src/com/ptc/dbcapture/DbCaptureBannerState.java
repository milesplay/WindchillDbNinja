package com.ptc.dbcapture;

/** Minimal capture state safe to show to every authenticated Windchill user. */
public record DbCaptureBannerState(boolean running, String captureId, String startedBy,
                                   long startedAtMillis, boolean administrator,
                                   boolean ownedByCurrentUser)
      implements java.io.Serializable {

   private static final long serialVersionUID = 1L;

   public boolean canStart() {
      return administrator && !running;
   }

   public boolean canStop() {
      return administrator && running && ownedByCurrentUser;
   }
}
