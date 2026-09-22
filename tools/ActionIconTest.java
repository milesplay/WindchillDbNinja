package com.custom.dbcapture;

import java.awt.image.BufferedImage;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import wt.util.resource.RBPseudo;

import com.ptc.core.components.rendering.RenderingContext;
import com.ptc.core.components.rendering.guicomponents.IconComponent;
import com.ptc.netmarkets.util.misc.NmAction;

public final class ActionIconTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected icon directory, Start filename and Stop filename.");
        }
        ImageIO.setUseCache(false);
        ResourceBundle resources = new dbCaptureActionResource();
        String[][] actions = {
            {"startDbCapture", args[1], "Ninja Trick", "Begin recording database changes"},
            {"stopDbCapture", args[2], "Ninja Stealth", "Stop recording and collect what changed"}
        };
        for (String[] entry : actions) {
            String key = "dbcapture." + entry[0];
            String resource = "dbcapture/" + entry[1];
            check(resource.equals(resources.getString(key + ".icon")), "Current bundle resolves the custom icon");
            RBPseudo pseudo = dbCaptureActionResource.class
                    .getField("startDbCapture".equals(entry[0]) ? "START_ICON" : "STOP_ICON")
                    .getAnnotation(RBPseudo.class);
            check(pseudo != null && !pseudo.value(), "Image paths are excluded from pseudo-localization");
            check(entry[2].equals(resources.getString(key + ".description")), "Action label is unchanged");
            check(entry[3].equals(resources.getString(key + ".tooltip")), "Action tooltip is unchanged");
            NmAction action = new NmAction();
            action.setType("dbcapture");
            action.setAction(entry[0]);
            action.setIcon(resource);
            action.setEnabled(true);
            String imagePath = "netmarkets/images/" + resource;
            check(imagePath.equals(action.getIcon()), "Native action resolves the standard image directory");
            check(action.getDisabledIcon() == null, "No separate disabled-image override is introduced");
            action.setEnabled(false);
            check(!action.isEnabled() && imagePath.equals(action.getIcon()),
                    "Disabled action retains the same image reference");

            BufferedImage image = ImageIO.read(Path.of(args[0], entry[1]).toFile());
            check(image != null && image.getWidth() == 16 && image.getHeight() == 16,
                    "JDK decoder sees the standard 16x16 image");
            check(image.getColorModel().hasAlpha(), "Image supports transparency");
            for (int[] point : new int[][] {{0, 0}, {15, 0}, {0, 15}, {15, 15}}) {
                check((image.getRGB(point[0], point[1]) >>> 24) == 0, "Corner is transparent");
            }
            int visible = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) if ((image.getRGB(x, y) >>> 24) != 0) visible++;
            }
            check(visible > 20, "Artwork is nonempty");
            IconComponent icon = new IconComponent(action.getIcon());
            icon.setTooltip(entry[3]);
            StringWriter html = new StringWriter();
            icon.draw(html, new RenderingContext());
            check(html.toString().contains(resource) && html.toString().contains("<img"),
                    "Installed PTC renderer emits the custom image");
            check(html.toString().contains(entry[3]), "Rendered icon preserves accessible guidance");
        }
        check("edit.gif".equals(resources.getString("dbcapture.editDbCaptureDescription.icon")),
                "Edit icon remains standard");
        check("delete.gif".equals(resources.getString("dbcapture.deleteDbCaptureSession.icon")),
                "Delete icon remains standard");
        check("export_list_to_csv.png".equals(resources.getString("dbcapture.exportDbCaptureChangesCsv.icon")),
                "CSV icon remains standard");
        System.out.println("PASS: " + assertions + " action-icon assertions; current resource source, target SDK, no database writes.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
