package com.ptc.dbcapture;

import com.ptc.core.ui.validation.DefaultSimpleValidationFilter;
import com.ptc.core.ui.validation.UIValidationCriteria;
import com.ptc.core.ui.validation.UIValidationKey;
import com.ptc.core.ui.validation.UIValidationStatus;

/**
 * Greys out whichever of Start / Stop does not apply right now.
 *
 * Monitoring is server-wide and there is only ever one session, so offering
 * Start while a capture is running - or Stop while none is - can only produce
 * an error message. Disabling rather than hiding is deliberate: the entries
 * stay in place so it is obvious the feature exists and which state it is in.
 *
 * Registered per action through {@code <includeFilter name="..."/>}; the
 * selector names are bound to these classes in DbCapture.service.properties.xconf.
 */
public abstract class DbCaptureStateFilter extends DefaultSimpleValidationFilter {

   /**
    * @return true when the action should be offered
    */
   protected abstract boolean isApplicable(DbCaptureBannerState state);

   @Override
   public UIValidationStatus preValidateAction(UIValidationKey key,
                                               UIValidationCriteria criteria) {
      try {
         DbCaptureBannerState state = DbCaptureHelper.service.getBannerState();
         return isApplicable(state) ? UIValidationStatus.ENABLED
                                    : UIValidationStatus.DISABLED;
      } catch (Exception e) {
         // Fail closed. Enabling Start or Stop when global state is unknown
         // can create the exact multi-user race this filter prevents.
         return UIValidationStatus.DISABLED;
      }
   }

   /** Start is offered only when nothing is being captured. */
   public static class NotRunning extends DbCaptureStateFilter {
      protected boolean isApplicable(DbCaptureBannerState state) {
         return !state.running();
      }
   }

   /** Stop is offered only to the same user who started the running capture. */
   public static class Running extends DbCaptureStateFilter {
      protected boolean isApplicable(DbCaptureBannerState state) {
         return state.canStop();
      }
   }
}
