package com.custom.dbcapture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.custom.dbcapture.engine.MonitoringScope;
import com.custom.dbcapture.engine.TableFilter;

import wt.util.WTProperties;

/** One-time, explicitly requested migration; run only in the approved maintenance window. */
public final class MigratePreferenceExclusion {
    private static final String TABLE = "PREFERENCEINSTANCE";

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("--check", "--apply").contains(args[0])) {
            throw new IllegalArgumentException("Usage: MigratePreferenceExclusion --check|--apply");
        }
        Properties before = DbCaptureSettings.load();
        String configured = WTProperties.getLocalProperties().getProperty(TableFilter.EXCLUDE_PROPERTY, "");
        Properties after = plan(before, configured);
        if (before.equals(after)) {
            System.out.println("No exact legacy PREFERENCEINSTANCE exclusion remains; nothing changed.");
            return;
        }
        if ("--check".equals(args[0])) {
            System.out.println("PASS: only the exact PREFERENCEINSTANCE extra exclusion would be removed."
                    + " Its new default exclusion remains active; no settings were written.");
            return;
        }
        if (!before.equals(DbCaptureSettings.load())) {
            throw new IllegalStateException("Settings changed during the check; migration refused.");
        }
        DbCaptureSettings.update(Map.of(DbCaptureSettings.EXTRA_EXCLUDES,
                after.getProperty(DbCaptureSettings.EXTRA_EXCLUDES)));
        if (!after.equals(DbCaptureSettings.load())) {
            throw new IllegalStateException("Migration readback differs; inspect the protected settings backup.");
        }
        System.out.println("PASS: exact legacy PREFERENCEINSTANCE exclusion migrated to the movable default."
                + " All other settings and exclusions are preserved.");
    }

    static Properties plan(Properties before, String configured) {
        if (!MonitoringScope.defaultExcludedTables().contains(TABLE)) {
            throw new IllegalStateException("The compiled candidate does not declare PREFERENCEINSTANCE as a default.");
        }
        Properties after = new Properties();
        after.putAll(before);
        List<String> excluded = new ArrayList<>(
                TableFilter.splitPatterns(before.getProperty(DbCaptureSettings.EXTRA_EXCLUDES)));
        if (!excluded.remove(TABLE)) return after;
        after.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, String.join(",", excluded));
        TableFilter filter = new TableFilter(after, configured);
        if (filter.lockedReason(TABLE) != null) {
            throw new IllegalArgumentException("Another explicit site/wildcard exclusion still locks PREFERENCEINSTANCE."
                    + " Nothing was changed.");
        }
        if (filter.isIncluded(TABLE)) {
            throw new IllegalArgumentException("PREFERENCEINSTANCE is already explicitly included."
                    + " Review its selected scope before migration; nothing was changed.");
        }
        return after;
    }
}
