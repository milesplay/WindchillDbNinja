package com.custom.dbcapture;

import java.io.Serializable;
import java.util.Objects;

/** Minimal capture state safe to show to every authenticated Windchill user. */
public final class DbCaptureBannerState implements Serializable {

   private static final long serialVersionUID = 1L;
   private final boolean running;
   private final String captureId;
   private final String startedBy;
   private final long startedAtMillis;
   private final boolean administrator;
   private final boolean ownedByCurrentUser;

   public DbCaptureBannerState(boolean running, String captureId, String startedBy,
                               long startedAtMillis, boolean administrator,
                               boolean ownedByCurrentUser) {
      this.running = running;
      this.captureId = captureId;
      this.startedBy = startedBy;
      this.startedAtMillis = startedAtMillis;
      this.administrator = administrator;
      this.ownedByCurrentUser = ownedByCurrentUser;
   }

   public boolean running() { return running; }
   public String captureId() { return captureId; }
   public String startedBy() { return startedBy; }
   public long startedAtMillis() { return startedAtMillis; }
   public boolean administrator() { return administrator; }
   public boolean ownedByCurrentUser() { return ownedByCurrentUser; }

   public boolean canStart() {
      return administrator && !running;
   }

   public boolean canStop() {
      return administrator && running && ownedByCurrentUser;
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof DbCaptureBannerState)) return false;
      DbCaptureBannerState that = (DbCaptureBannerState) other;
      return running == that.running && startedAtMillis == that.startedAtMillis
            && administrator == that.administrator && ownedByCurrentUser == that.ownedByCurrentUser
            && Objects.equals(captureId, that.captureId) && Objects.equals(startedBy, that.startedBy);
   }

   @Override
   public int hashCode() {
      return Objects.hash(running, captureId, startedBy, startedAtMillis, administrator, ownedByCurrentUser);
   }

   @Override
   public String toString() {
      return "DbCaptureBannerState[running=" + running + ", captureId=" + captureId
            + ", startedBy=" + startedBy + ", startedAtMillis=" + startedAtMillis
            + ", administrator=" + administrator + ", ownedByCurrentUser=" + ownedByCurrentUser + "]";
   }
}
