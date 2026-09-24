package com.custom.dbcapture;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import wt.fc.ReferenceFactory;
import wt.httpgw.URLFactory;
import wt.inf.container.WTContainerHelper;
import wt.util.HTMLEncoder;
import wt.util.WTException;

/** A Site-context result link shared by the header endpoint and framework Stop action. */
public final class DbCaptureNavigation {
   private DbCaptureNavigation() {
   }

   public static String resultsUrl(String captureId) throws WTException {
      String site = new ReferenceFactory().getReferenceString(WTContainerHelper.getExchangeRef());
      return resultsUrl(new URLFactory().getHREF("app/"), site, captureId);
   }

   static String resultsUrl(String shellUrl, String siteOid, String captureId) {
      if (captureId == null || !captureId.matches("CAP-[0-9]{1,36}")) {
         throw new IllegalArgumentException("Invalid completed capture ID.");
      }
      String capture = URLEncoder.encode(captureId, StandardCharsets.UTF_8);
      String site = URLEncoder.encode(siteOid, StandardCharsets.UTF_8);
      // Changing the shell query refreshes cached Quick Links states as well as the results.
      return shellUrl + "?dbcResults=" + capture
            + "#ptc1/dbcapture/dbCaptureAdmin?ContainerOid=" + site + "&oid=" + site
            + "&u8=1&dbcCompletedCapture=" + capture;
   }

   static String redirectScript(String url) {
      return "(window.top || window).location.assign(\""
            + HTMLEncoder.encodeForJavascript(url) + "\");";
   }
}
