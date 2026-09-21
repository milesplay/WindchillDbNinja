package com.ptc.dbcapture;

import wt.util.resource.RBEntry;
import wt.util.resource.RBPseudo;
import wt.util.resource.RBUUID;
import wt.util.resource.WTListResourceBundle;

/** Labels and tooltips for the DB Capture actions. */
@RBUUID("com.ptc.dbcapture.dbCaptureActionResource")
public final class dbCaptureActionResource extends WTListResourceBundle {

   @RBEntry("Ninja Trick")
   public static final String START_DESCRIPTION = "dbcapture.startDbCapture.description";

   @RBEntry("Begin recording database changes")
   public static final String START_TOOLTIP = "dbcapture.startDbCapture.tooltip";

   // Action icons are relative to netmarkets/images, not the web root.
   @RBPseudo(false)
   @RBEntry("dbcapture/dbNinjaTrick-v20260921.png")
   public static final String START_ICON = "dbcapture.startDbCapture.icon";

   @RBEntry("Ninja Stealth")
   public static final String STOP_DESCRIPTION = "dbcapture.stopDbCapture.description";

   @RBEntry("Stop recording and collect what changed")
   public static final String STOP_TOOLTIP = "dbcapture.stopDbCapture.tooltip";

   @RBPseudo(false)
   @RBEntry("dbcapture/dbNinjaStealth-v20260921.png")
   public static final String STOP_ICON = "dbcapture.stopDbCapture.icon";

   @RBEntry("DB Ninja")
   public static final String ADMIN_DESCRIPTION = "dbcapture.dbCaptureAdmin.description";

   @RBEntry("Search database changes recorded by DB Ninja")
   public static final String ADMIN_TOOLTIP = "dbcapture.dbCaptureAdmin.tooltip";

   @RBEntry("Minor Tab: DB Ninja")
   public static final String ADMIN_TAB_TOOLTIP = "site.dbCaptureAdmin.tooltip";

   @RBEntry("Edit Description")
   public static final String EDIT_DESC_DESCRIPTION =
         "dbcapture.editDbCaptureDescription.description";

   @RBEntry("Give this capture a name you will recognise later")
   public static final String EDIT_DESC_TOOLTIP =
         "dbcapture.editDbCaptureDescription.tooltip";

   @RBEntry("edit.gif")
   public static final String EDIT_DESC_ICON =
         "dbcapture.editDbCaptureDescription.icon";

   @RBEntry("Delete")
   public static final String DELETE_DESCRIPTION =
         "dbcapture.deleteDbCaptureSession.description";

   @RBEntry("Delete the selected capture sessions and their recorded changes")
   public static final String DELETE_TOOLTIP =
         "dbcapture.deleteDbCaptureSession.tooltip";

   @RBEntry("delete.gif")
   public static final String DELETE_ICON =
         "dbcapture.deleteDbCaptureSession.icon";

   @RBEntry("Delete the selected capture sessions and everything they recorded?")
   public static final String CONFIRM_DELETE = "CONFIRM_DELETE";

   @RBEntry("DB Ninja")
   public static final String HEADER_GROUP = "dbcapture.group.description";

   @RBEntry("Export Database Changes as CSV")
   public static final String CSV_DESCRIPTION = "dbcapture.exportDbCaptureChangesCsv.description";

   @RBEntry("Download only the displayed rows and columns; filename uses Description or Capture ID")
   public static final String CSV_TOOLTIP = "dbcapture.exportDbCaptureChangesCsv.tooltip";

   @RBEntry("export_list_to_csv.png")
   public static final String CSV_ICON = "dbcapture.exportDbCaptureChangesCsv.icon";
}
