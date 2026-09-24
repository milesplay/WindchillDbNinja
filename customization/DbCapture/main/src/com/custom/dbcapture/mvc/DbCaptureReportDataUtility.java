package com.custom.dbcapture.mvc;

import java.util.ArrayList;
import java.util.List;

import com.ptc.core.components.descriptor.ModelContext;
import com.ptc.core.components.factory.AbstractDataUtility;
import com.ptc.core.components.rendering.guicomponents.TextArea;
import com.ptc.core.components.rendering.guicomponents.TextDisplayComponent;
import com.ptc.core.components.rendering.guicomponents.GUIComponentArray;
import com.ptc.core.components.rendering.guicomponents.GuiComponentUtil;
import com.ptc.core.components.rendering.guicomponents.UrlDisplayComponent;
import com.custom.dbcapture.DbCapturePresentation;
import com.custom.dbcapture.DbCaptureTableChange;

import jakarta.servlet.http.HttpServletRequest;
import wt.fc.PersistenceHelper;
import wt.util.WTContext;
import wt.util.WTException;

/** Scoped to DbCaptureTableChange; never overrides another customization's columns. */
public final class DbCaptureReportDataUtility extends AbstractDataUtility {
   @Override
   public void setModelData(String componentId, List<?> data, ModelContext context) throws WTException {
      if (data.isEmpty()) return;
      HttpServletRequest request = context.getNmCommandBean().getRequest();
      Object existing = request.getAttribute(DbCapturePresentation.REQUEST_KEY);
      if (!(existing instanceof DbCapturePresentation)) {
         List<DbCaptureTableChange> rows = new ArrayList<>();
         for (Object value : data) {
            if (value instanceof DbCaptureTableChange) rows.add((DbCaptureTableChange) value);
         }
         existing = DbCapturePresentation.load(rows);
         request.setAttribute(DbCapturePresentation.REQUEST_KEY, existing);
      }
   }

   @Override
   public Object getDataValue(String componentId, Object datum, ModelContext context) throws WTException {
      if (!(datum instanceof DbCaptureTableChange)) {
         throw new WTException("Unexpected DB Capture report row: " + datum);
      }
      DbCaptureTableChange row = (DbCaptureTableChange) datum;
      HttpServletRequest request = context.getNmCommandBean().getRequest();
      Object report = request.getAttribute(DbCapturePresentation.REQUEST_KEY);
      if (!(report instanceof DbCapturePresentation)
            || !((DbCapturePresentation) report).containsCapture(row.getCaptureId())) {
         report = DbCapturePresentation.load(List.of(row));
         request.setAttribute(DbCapturePresentation.REQUEST_KEY, report);
      }
      DbCapturePresentation presentation = (DbCapturePresentation) report;
      String value;
      String tooltip;
      switch (componentId) {
         case "objectIdentities":
            GUIComponentArray links = new GUIComponentArray();
            links.setDelimiter(GuiComponentUtil.Delimiter.LINE_BREAK);
            links.setNoWrap(false);
            for (DbCapturePresentation.ObjectLink object : presentation.objectLinks(row)) {
               UrlDisplayComponent link = new UrlDisplayComponent();
               link.setLabelForTheLink(object.reference());
               link.setLink(new wt.httpgw.URLFactory().getHREF(
                     "netmarkets/jsp/dbcapture/objectDetails.jsp?entry=" + object.deltaId()));
               link.setTarget("_blank");
               link.setFullyQualified(true);
               link.setToolTip(presentation.objectReferenceTooltip(row));
               links.addGUIComponent(link);
            }
            return links.size() == 0 ? TextDisplayComponent.NBSP : links;
         case "details":
            value = presentation.changedColumns(row);
            if (value.isEmpty()) return TextDisplayComponent.NBSP;
            TextArea area = scrollableText("dbc-delta-"
                  + PersistenceHelper.getObjectIdentifier(row).getId(), value);
            area.setTooltip("Recorded changed columns and old/new values. Read-only; scroll to see more.");
            return area;
         case "dbcObjectName":
            value = presentation.objectNames(row);
            tooltip = presentation.identityTooltip(row, true);
            break;
         case "dbcObjectNumber":
            value = presentation.objectNumbers(row);
            tooltip = presentation.identityTooltip(row, false);
            break;
         case "dbcObjectCount":
            value = Integer.toString(presentation.objectCount(row));
            tooltip = "Number of distinct entries represented in the Object column.";
            break;
         case "lastChangeTime":
         case "firstChangeTime":
            value = presentation.changedAt(row, "firstChangeTime".equals(componentId),
                  context.getLocale(), WTContext.getContext().getTimeZone());
            tooltip = presentation.changedAtTooltip(row, "firstChangeTime".equals(componentId));
            break;
         default:
            throw new WTException("Unsupported DB Capture report column: " + componentId);
      }
      TextDisplayComponent text = new TextDisplayComponent(componentId);
      text.setValue(value);
      text.setTooltip(tooltip);
      text.disableMoreLink();
      text.setTruncationLength(0);
      text.setCreateHyperlinks(false);
      return text;
   }

   public static TextArea scrollableText(String id, String value) {
      TextArea area = new TextArea();
      area.setId(id);
      area.setName(id);
      area.setValue(value);
      area.setWidth(60);
      area.setHeight(5);
      area.setEnabled(true);
      area.setEditable(true);
      area.setReadOnly(true);
      area.setRichText(false);
      area.addStyleClass("dbcRecordedText");
      return area;
   }
}
