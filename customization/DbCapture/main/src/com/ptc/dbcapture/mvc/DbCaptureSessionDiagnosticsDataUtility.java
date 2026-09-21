package com.ptc.dbcapture.mvc;

import com.ptc.core.components.descriptor.ModelContext;
import com.ptc.core.components.factory.AbstractDataUtility;
import com.ptc.core.components.rendering.guicomponents.IconComponent;
import com.ptc.core.components.rendering.guicomponents.GUIComponentArray;
import com.ptc.core.components.rendering.guicomponents.TextArea;
import com.ptc.core.components.rendering.guicomponents.TextDisplayComponent;
import com.ptc.core.components.rendering.guicomponents.UrlDisplayComponent;
import com.ptc.dbcapture.DbCaptureSession;
import com.ptc.dbcapture.diagnostics.CaptureHealth;
import wt.fc.PersistenceHelper;
import wt.util.WTException;

/** Links identify the persisted capture, not the human-readable ID which can be reused. */
public final class DbCaptureSessionDiagnosticsDataUtility extends AbstractDataUtility {
   @Override
   public Object getDataValue(String componentId, Object datum, ModelContext context) throws WTException {
      if (!(datum instanceof DbCaptureSession)) {
         throw new WTException("Expected a DB Capture session for the diagnostic link.");
      }
      DbCaptureSession capture = (DbCaptureSession) datum;
      CaptureHealth.Assessment health = CaptureHealth.assess(capture.getStatus(), capture.getCaptureMode(),
            capture.getWarnings(), capture.getErrorText());
      if ("status".equals(componentId)) {
         IconComponent icon = new IconComponent();
         String image = switch (health.tone()) {
            case GREEN -> "green.gif";
            case YELLOW -> "yellow.gif";
            case RED -> "red.gif";
         };
         icon.setSrc(new wt.httpgw.URLFactory().getHREF("netmarkets/images/" + image));
         icon.setTooltip(health.label() + ": " + health.explanation()
               + " Saved status: " + capture.getStatus() + ".");
         icon.setInternalValue(health.tone().ordinal());
         TextDisplayComponent label = new TextDisplayComponent("dbcStatusLabel");
         label.setValue(health.label());
         label.addStyleClass("dbcStatusLabel");
         label.setCreateHyperlinks(false);
         GUIComponentArray status = new GUIComponentArray();
         status.addGUIComponent(icon);
         status.addGUIComponent(label);
         return status;
      }
      if ("warnings".equals(componentId)) {
         if (health.summary().isEmpty()) return TextDisplayComponent.NBSP;
         TextArea text = DbCaptureReportDataUtility.scrollableText("dbc-warning-"
               + capture.getCaptureId(), health.summary());
         text.setHeight(3);
         text.setWidth(80);
         text.addStyleClass("dbcWarningSummary");
         text.setTooltip("Cause / impact / next check. Full saved warnings and errors: SQL / Stack Trace.");
         return text;
      }
      wt.fc.ObjectIdentifier oid = PersistenceHelper.getObjectIdentifier(capture);
      if (oid == null || oid.getId() <= 0) throw new WTException("Capture has not been persisted.");
      if (!"dbcCaptureSql".equals(componentId) && !"dbcCaptureStack".equals(componentId)) {
         throw new WTException("Unknown DB Capture diagnostic column: " + componentId);
      }
      UrlDisplayComponent link = new UrlDisplayComponent();
      link.setLabelForTheLink("SQL / Stack Trace");
      link.setLink(new wt.httpgw.URLFactory().getHREF(
            "netmarkets/jsp/dbcapture/captureDiagnostics.jsp?capture=" + oid.getId()));
      link.setTarget("_blank");
      link.setFullyQualified(true);
      link.setToolTip("Open AJP SQL, call trees and full capture diagnostics for " + capture.getCaptureId() + ".");
      return link;
   }
}
