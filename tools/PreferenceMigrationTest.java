package com.custom.dbcapture;

import java.util.Properties;

public final class PreferenceMigrationTest {
    public static void main(String[] args) {
        Properties before = new Properties();
        before.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, "PREFERENCEINSTANCE,WTPART,TEMP_*");
        before.setProperty(DbCaptureSettings.INCLUDED_TABLES, "");
        before.setProperty(DbCaptureSettings.INCLUDE_QUEUE, "false");
        before.setProperty("unrelatedPreservedValue", "literal value");
        Properties after = MigratePreferenceExclusion.plan(before, "");
        check(after.getProperty(DbCaptureSettings.EXTRA_EXCLUDES).equals("WTPART,TEMP_*"));
        check(before.getProperty(DbCaptureSettings.EXTRA_EXCLUDES).startsWith("PREFERENCEINSTANCE,"));
        check(after.getProperty(DbCaptureSettings.INCLUDE_QUEUE).equals("false"));
        check(after.getProperty("unrelatedPreservedValue").equals("literal value"));
        check(MigratePreferenceExclusion.plan(after, "").equals(after));
        rejects(before, "PREFERENCEINSTANCE");
        Properties wildcard = new Properties();
        wildcard.putAll(before);
        wildcard.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, "PREFERENCEINSTANCE,*PREFERENCE*");
        rejects(wildcard, "");
        Properties enabled = new Properties();
        enabled.putAll(before);
        enabled.setProperty(DbCaptureSettings.INCLUDED_TABLES, "PREFERENCEINSTANCE");
        rejects(enabled, "");
        System.out.println("PASS: 8 isolated preference-exclusion migration assertions; no file or database writes.");
    }

    private static void rejects(Properties values, String site) {
        Properties copy = new Properties();
        copy.putAll(values);
        try {
            MigratePreferenceExclusion.plan(values, site);
            throw new AssertionError("Conflicting exclusions or opt-ins must not be overridden.");
        } catch (IllegalArgumentException expected) {
            check(copy.equals(values));
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Preference migration did not preserve the expected scope.");
    }
}
