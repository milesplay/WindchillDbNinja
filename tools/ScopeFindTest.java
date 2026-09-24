package com.custom.dbcapture.engine;

import com.custom.dbcapture.DbCaptureSearch;
import com.custom.dbcapture.DbCaptureSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import wt.util.WTProperties;

public final class ScopeFindTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Locale previous = Locale.getDefault();
        try {
            for (Locale locale : List.of(Locale.US, Locale.JAPAN, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale);
                scope();
                keywords();
            }
        } finally {
            Locale.setDefault(previous);
        }
        exhaustive();
        settings();
        System.out.println("PASS: " + assertions + " scope/search assertions; no live settings or business data changed.");
    }

    private static void scope() {
        for (int combination = 0; combination < 8; combination++) {
            Properties p = new Properties();
            p.setProperty(DbCaptureSettings.INCLUDE_MONITORING, "" + ((combination & 1) != 0));
            p.setProperty(DbCaptureSettings.INCLUDE_QUEUE, "" + ((combination & 2) != 0));
            p.setProperty(DbCaptureSettings.INCLUDE_STATISTICS, "" + ((combination & 4) != 0));
            TableFilter filter = new TableFilter(p, "");
            check(filter.isIncluded("WTPART") && filter.isIncluded("wtpartmaster"), "business objects included");
            for (String table : List.of("METHODCONTEXTS", "SERVLETREQUESTS", "MISCLOGEVENTS")) {
                check(filter.isIncluded(table) == ((combination & 1) != 0), "monitoring switch " + table);
            }
            for (String table : List.of("QUEUEENTRY", "SCHEDULEQUEUE", "QUEUELOCK", "PROCESSINGQUEUE",
                    "SCHEDULEQUEUEENTRY", "JOBTOQUEUEENTRYLINK", "DEFERREDEVENTNOTIFICATION")) {
                check(filter.isIncluded(table) == ((combination & 2) != 0), "queue/scheduler switch " + table);
            }
            for (String table : List.of("METHODCONTEXTSTATS", "REQUESTHISTOGRAMS", "PAGERESULTS",
                    "PQSTATS", "SQSTATS")) {
                check(filter.isIncluded(table) == ((combination & 4) != 0), "statistics switch " + table);
            }
            check(filter.isIncluded("TOPSQLSTATS") == ((combination & 1) != 0 && (combination & 4) != 0),
                    "overlapping monitoring/statistics exclusions are explicit");
            for (String table : List.of("DBCAPTURESESSION", "DBCAPTURECHANGE", "DBCAPTUREATTRDELTA",
                    "DBCAPTURETABLECHANGE", "DBCAPTURESQLEVENTS", "WTUPGINSTALL", "WCTK_TEMP",
                    "BIN$ABC$0", "bad.table", "X;DROP", "\"WTPART\"", "")) {
                check(!filter.isIncluded(table), "always excluded or invalid table " + table);
            }
            check(!filter.isIncluded(null), "null table excluded");
            for (String table : List.of("QUEUEEVENTINFO", "QUEUEENTRYEVENTINFO", "WFSTATEEVENTAUDIT",
                    "TASKEVENT", "RECENTUPDATE", "CLIENTCACHESTATE", "INDEXSTATUS",
                    "CUSTOMBUSINESSSTATS", "CUSTOMHISTOGRAMS", "QUEUEBUSINESSDATA")) {
                check(filter.isIncluded(table), "no broad default hiding business/audit data: " + table);
            }
            for (String table : List.of("PAGINGSESSION", "COLLECTORCACHE", "SHADOWCACHE",
                    "FVMOUNTVALIDATORLOCK", "SCH_ENTRIES", "SCHEDULEHISTORY", "PREFERENCEINSTANCE")) {
                check(!filter.isIncluded(table), "new audited defaults do not silently inherit legacy enables: " + table);
            }
        }
        Properties p = new Properties();
        p.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, " wtpart, foo*, *link, *hist*,wtpart ");
        TableFilter filter = new TableFilter(p, "CONTROLBRANCH");
        for (String excluded : List.of("WTPART", "FOOBAR", "WTLINK", "HISTORY", "CONTROLBRANCH")) {
            check(!filter.isIncluded(excluded), "combined extra/property exclusions");
        }
        check(filter.isIncluded("WTPARTMASTER"), "exact exclusions don't become accidental prefixes");
        check(TableFilter.splitPatterns("wtpart, WTPART,, indexstatus ").equals(List.of("WTPART", "INDEXSTATUS")),
                "case normalization and duplicate removal");
        check(TableFilter.matchesAny("ANY", TableFilter.splitPatterns("*")), "bare wildcard excludes all without throwing");
        check(TableFilter.matchesAny("WTPARTMASTER", List.of("wtpart*")), "pattern normalization independent of caller");
        for (String invalid : List.of("WT?PART", "WT*PART", "WTPART;DROP", "USER.TABLE", "A B", "**")) {
            expectFailure(() -> TableFilter.splitPatterns(invalid), IllegalArgumentException.class);
        }
        check(TableFilter.patternsForGroups(TableFilter.splitKeys("TASKevents,RECENTUPDATES"))
                .containsAll(List.of("TASKEVENT*", "RECENTUPDATE*")), "display group keys are case-insensitive");
        check(TableFilter.resolveHidden(List.of("WTPART", "QUEUEENTRY", "RECENTUPDATE"),
                TableFilter.patternsForGroups(List.of("queue"))).equals(List.of("QUEUEENTRY")),
                "display hide list resolves only actual known names");
        p.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, "*");
        TableFilter allOut = new TableFilter(p, "");
        check(!allOut.isIncluded("WTPART"), "explicit all-excluded scope remains safe");
        p.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, "");
        check(!allOut.isIncluded("WTPART"), "scope instance is immutable after source properties change");
        expectFailure(() -> allOut.getPatterns().add("X"), UnsupportedOperationException.class);
        p = new Properties();
        p.setProperty(DbCaptureSettings.INCLUDE_QUEUE, "true");
        p.setProperty(DbCaptureSettings.INCLUDED_TABLES, "");
        check(!new TableFilter(p, "").isIncluded("QUEUEENTRY"), "explicit empty overrides legacy true");
        p.setProperty(DbCaptureSettings.INCLUDED_TABLES, "queueentry, pagingSession,queueentry");
        TableFilter exact = new TableFilter(p, "");
        check(exact.isIncluded("QUEUEENTRY") && exact.isIncluded("PAGINGSESSION"),
                "per-table includes normalize and enable only selected defaults");
        check(!exact.isIncluded("QUEUELOCK"), "selection is not a group switch");
        p.setProperty(DbCaptureSettings.INCLUDED_TABLES, "");
        check(exact.isIncluded("QUEUEENTRY"), "Start-time exact overrides remain frozen");
        check(!new TableFilter(p, "").isIncluded("QUEUEENTRY"), "next Start reads new settings");
        expectFailure(() -> exact.getRequestedIncludes().clear(), UnsupportedOperationException.class);
        check(TableFilter.splitExactNames(" queueentry, QueueEntry,pagingSession ")
                .equals(List.of("PAGINGSESSION", "QUEUEENTRY")), "exact parser is canonical");
        for (String invalid : List.of("*", "QUEUE*", "*ENTRY", "QUE?UE", "SCHEMA.QUEUEENTRY",
                "\"QUEUEENTRY\"", "QUEUEENTRY;DROP", "A B", "A".repeat(129))) {
            expectFailure(() -> TableFilter.splitExactNames(invalid), IllegalArgumentException.class);
        }
        p.setProperty(DbCaptureSettings.INCLUDED_TABLES, "QUEUEENTRY");
        p.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, "QUEUE*");
        check(!new TableFilter(p, "").isIncluded("QUEUEENTRY"), "extra exclusions beat exact includes");
        p.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, "");
        check(!new TableFilter(p, "QUEUEENTRY").isIncluded("QUEUEENTRY"),
                "site exclusions beat exact includes");
        TableFilter bounded = new TableFilter(new Properties(), "");
        check(bounded.isIncluded("T".repeat(40)) && !bounded.isIncluded("T".repeat(41)),
                "table identifiers fit the persisted capture width without truncation");
    }

    private static void keywords() {
        check(DbCaptureSearch.forKeyword(null) == null && DbCaptureSearch.forKeyword("  \t") == null,
                "empty keyword means no filter");
        check(DbCaptureSearch.forKeyword(" INdex ").matches("INDEXSTATUS"), "Locale.ROOT case folding");
        check(DbCaptureSearch.forKeyword("wt").matches("prefix WTPARTMASTER suffix"), "plain substring");
        check(!DbCaptureSearch.forKeyword("wt*").matches("prefix wtpart"), "glob anchors the whole field");
        check(DbCaptureSearch.forKeyword("*master*").matches("WTPARTMASTER"), "contains glob");
        check(DbCaptureSearch.forKeyword("WTPART?").matches("WTPART1"), "single wildcard");
        check(!DbCaptureSearch.forKeyword("WTPART?").matches("WTPART12"), "single wildcard is not arbitrary length");
        check(DbCaptureSearch.forKeyword("CAP-*").matches("name\nCAP-000002\nUPDATE"), "glob per newline field");
        check(DbCaptureSearch.forKeyword("wt.part.*:2").matches("wt.part.WTPart:1, wt.part.WTPart:2"),
                "comma-separated identities");
        check(DbCaptureSearch.forKeyword("日本*").matches("日本語"), "Unicode keyword");
        check(DbCaptureSearch.forKeyword("x?z").matches("x\uD83D\uDE00z"), "question mark matches one Unicode code point");
        for (String literal : List.of("a.b", "[x]", "(x)", "a+b", "^x$", "\\E", "100%", "a_b", "a|b", "<script>")) {
            check(DbCaptureSearch.forKeyword("*" + literal + "*").matches("prefix " + literal + " suffix"),
                    "regex/HTML punctuation remains literal");
        }
        check(!DbCaptureSearch.forKeyword("*a.b*").matches("aXb"), "literal dot not regex");
        check(!DbCaptureSearch.forKeyword("anything").matches(null), "null data does not match");
        String veryLong = "long_keyword_".repeat(300);
        check(DbCaptureSearch.forKeyword(veryLong).matches(veryLong), "long literal keyword retained");
        check(!DbCaptureSearch.forKeyword("*a".repeat(100) + "z").matches("a".repeat(3000)),
                "hostile wildcard pattern terminates without regex backtracking");
    }

    private static void exhaustive() {
        List<String> terms = strings("ab*?", 4);
        List<String> values = strings("ab", 5);
        for (String term : terms) {
            DbCaptureSearch search = DbCaptureSearch.forKeyword(term);
            for (String value : values) {
                boolean expected;
                if (term.isEmpty()) expected = true;
                else if (!term.contains("*") && !term.contains("?")) expected = value.contains(term);
                else {
                    String regex = "";
                    for (char c : term.toCharArray()) regex += c == '*' ? ".*" : c == '?' ? "." : Pattern.quote("" + c);
                    expected = value.matches(regex);
                }
                check((search == null || search.matches(value)) == expected, "exhaustive glob " + term + " / " + value);
            }
        }
    }

    private static List<String> strings(String alphabet, int length) {
        List<String> out = new ArrayList<>(List.of(""));
        List<String> previous = List.of("");
        for (int i = 0; i < length; i++) {
            List<String> next = new ArrayList<>();
            for (String prefix : previous) for (char c : alphabet.toCharArray()) next.add(prefix + c);
            out.addAll(next);
            previous = next;
        }
        return out;
    }

    private static void settings() throws Exception {
        WTProperties props = WTProperties.getLocalProperties();
        String previousHome = props.getProperty("wt.home");
        Path temporary = Files.createDirectories(
                Path.of("validation", "scope-find-home-" + java.util.UUID.randomUUID()).toAbsolutePath());
        Path file = temporary.resolve("custom/DbCapture/settings.properties");
        try {
            props.setProperty("wt.home", temporary.toString());
            check(DbCaptureSettings.load().isEmpty(), "absent file uses documented defaults");
            check(!Files.exists(file.getParent()), "reading defaults does not create directories");
            DbCaptureSettings.update(Map.of(DbCaptureSettings.INCLUDE_MONITORING, "TRUE",
                    DbCaptureSettings.INCLUDE_QUEUE, "false", DbCaptureSettings.INCLUDE_STATISTICS, "true",
                    DbCaptureSettings.EXTRA_EXCLUDES, "wtpart, WTPart"));
            check(DbCaptureSettings.isEnabled(DbCaptureSettings.INCLUDE_MONITORING, false), "saved boolean persisted");
            check(DbCaptureSettings.text(DbCaptureSettings.EXTRA_EXCLUDES).equals("WTPART"), "saved pattern normalized");
            byte[] before = Files.readAllBytes(file);
            expectFailure(() -> DbCaptureSettings.update(Map.of(DbCaptureSettings.INCLUDE_QUEUE, "true",
                    DbCaptureSettings.EXTRA_EXCLUDES, "A*B")), IllegalArgumentException.class);
            check(java.util.Arrays.equals(before, Files.readAllBytes(file)), "invalid batch never partially saves");
            expectFailure(() -> DbCaptureSettings.set(DbCaptureSettings.INCLUDE_QUEUE, "maybe"), IllegalArgumentException.class);
            expectFailure(() -> DbCaptureSettings.set("unknown", "true"), IllegalArgumentException.class);
            Properties saved = DbCaptureSettings.load();
            saved.setProperty("unrelated.extension", "preserve");
            DbCaptureSettings.save(saved);
            DbCaptureSettings.set(DbCaptureSettings.INCLUDE_QUEUE, "true");
            check(DbCaptureSettings.load().getProperty("unrelated.extension").equals("preserve"),
                    "partial update preserves unrelated custom properties");
            perTableSettings(file);
            try (var paths = Files.list(file.getParent())) {
                check(paths.count() == 1, "atomic save leaves no temporary files");
            }
            Files.delete(file);
            Files.createDirectory(file);
            expectFailure(DbCaptureSettings::load, java.io.UncheckedIOException.class);
            Files.delete(file);
        } finally {
            if (previousHome == null) props.remove("wt.home"); else props.setProperty("wt.home", previousHome);
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(temporary.resolve("custom"));
            Files.deleteIfExists(temporary);
        }
    }

    private static void perTableSettings(Path file) throws Exception {
        Map<String, Boolean> tables = new java.util.TreeMap<>();
        for (String name : List.of("QUEUEENTRY", "QUEUELOCK", "SCHEDULEQUEUE", "SCHEDULEHISTORY",
                "PAGINGSESSION", "COLLECTORCACHE", "WTPART", "DBCAPTURESESSION", "QUEUEEVENTINFO",
                "CUSTOMBUSINESSSTATS", "SITEEXCLUDED", "PREFERENCEINSTANCE")) {
            tables.put(name, true);
        }
        tables.put("METHODCONTEXTS", false);
        tables.put("TOPSQLSTATS", false);
        tables.put("PAGERESULTS", false);
        tables.put("CUSTOM_NO_ID", false);
        MonitoringScope.Catalog catalog = new MonitoringScope.Catalog(tables);
        tables.put("LATER_TABLE", true);
        check(!catalog.contains("LATER_TABLE"), "schema catalog is immutable after construction");

        Properties legacy = DbCaptureSettings.load();
        legacy.setProperty(DbCaptureSettings.INCLUDE_QUEUE, "true");
        legacy.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, " WTPART , COLLECTORCACHE ");
        legacy.setProperty("unrelated.extension", "preserve exactly");
        DbCaptureSettings.save(legacy);
        MonitoringScope.Selection old = MonitoringScope.describe(legacy, "SITEEXCLUDED", catalog);
        check(old.getIncluded().equals(List.of("QUEUEENTRY", "QUEUELOCK", "SCHEDULEQUEUE")),
                "legacy right-hand list materializes only eligible physical defaults");
        check(old.getNotice().contains("Legacy enabled groups") && old.getNotice().contains("extraExcludeTables")
                && old.getNotice().contains(TableFilter.EXCLUDE_PROPERTY), "legacy/site exclusions explained");
        check(old.getLocked().containsAll(List.of("METHODCONTEXTS", "PAGERESULTS", "CUSTOM_NO_ID",
                "DBCAPTURESESSION", "WTPART", "COLLECTORCACHE", "SITEEXCLUDED")),
                "uncapturable/hard/site tables are locked");
        check(old.getReasons().get("METHODCONTEXTS").contains("No IDA2A2"),
                "monitoring without IDA2A2 is not offered as an ineffective opt-in");
        check(!old.getExcluded().contains("WTPART") && !old.getIncluded().contains("METHODCONTEXTS"),
                "locked tables never occur on either movable side");
        check(!old.getReasons().containsKey("QUEUEEVENTINFO")
                && !old.getReasons().containsKey("CUSTOMBUSINESSSTATS"), "business/audit data remains in scope");
        check(old.getExcluded().contains("PREFERENCEINSTANCE") && !old.getLocked().contains("PREFERENCEINSTANCE"),
                "preference instances are movable defaults when not explicitly excluded");

        MonitoringScope.Selection saved = DbCaptureSettings.updateIncludedTables(
                "queueentry,pagingSession,preferenceInstance,QUEUEENTRY", catalog, "SITEEXCLUDED");
        check(saved.getIncluded().equals(List.of("PAGINGSESSION", "PREFERENCEINSTANCE", "QUEUEENTRY")),
                "save returns full desired selection including preference opt-in");
        check(saved.getExcluded().contains("QUEUELOCK"), "legacy enabled group no longer re-adds unselected tables");
        Properties roundTrip = DbCaptureSettings.load();
        check(roundTrip.getProperty(DbCaptureSettings.INCLUDED_TABLES).equals("PAGINGSESSION,PREFERENCEINSTANCE,QUEUEENTRY"),
                "exact includes persist canonically");
        check(roundTrip.getProperty(DbCaptureSettings.EXTRA_EXCLUDES).equals(" WTPART , COLLECTORCACHE ")
                && roundTrip.getProperty("unrelated.extension").equals("preserve exactly")
                && roundTrip.getProperty(DbCaptureSettings.INCLUDE_QUEUE).equals("true"),
                "saving exact includes preserves legacy, raw extra exclusions and unrelated values");
        MonitoringScope.Selection readBack = MonitoringScope.describe(roundTrip, "SITEEXCLUDED", catalog);
        check(saved.getIncluded().equals(readBack.getIncluded()) && saved.getExcluded().equals(readBack.getExcluded())
                && saved.getLocked().equals(readBack.getLocked()) && saved.getReasons().equals(readBack.getReasons()),
                "scope response round trips exactly");
        TableFilter frozen = new TableFilter(roundTrip, "SITEEXCLUDED");
        Map<String, String> notRecorded = catalog.excludedReasons(frozen);
        check(!notRecorded.containsKey("QUEUEENTRY") && !notRecorded.containsKey("PREFERENCEINSTANCE")
                && notRecorded.containsKey("QUEUELOCK")
                && notRecorded.get("CUSTOM_NO_ID").contains("No IDA2A2"), "diagnostics includes no-ID locked scope");

        byte[] before = Files.readAllBytes(file);
        for (String invalid : java.util.Arrays.asList(null, "QUEUE*", "QUEUEENTRY,UNKNOWN", "WTPART",
                "DBCAPTURESESSION", "METHODCONTEXTS", "COLLECTORCACHE", "SITEEXCLUDED",
                "QUEUEEVENTINFO", "CUSTOMBUSINESSSTATS")) {
            expectFailure(() -> DbCaptureSettings.updateIncludedTables(invalid, catalog, "SITEEXCLUDED"),
                    IllegalArgumentException.class);
            check(java.util.Arrays.equals(before, Files.readAllBytes(file)), "invalid selection leaves file byte-identical");
        }
        expectFailure(() -> DbCaptureSettings.set(DbCaptureSettings.INCLUDED_TABLES, "QUEUEENTRY"),
                IllegalArgumentException.class);
        check(java.util.Arrays.equals(before, Files.readAllBytes(file)), "uncatalogued update path cannot bypass validation");
        DbCaptureSettings.updateIncludedTables("", catalog, "SITEEXCLUDED");
        check(MonitoringScope.describe(DbCaptureSettings.load(), "SITEEXCLUDED", catalog).getIncluded().isEmpty(),
                "empty exact selection supersedes all legacy group enables");
        check(frozen.isIncluded("QUEUEENTRY") && frozen.isIncluded("PAGINGSESSION")
                && frozen.isIncluded("PREFERENCEINSTANCE"),
                "saving during a capture does not mutate its frozen filter");
        check(notRecorded.equals(catalog.excludedReasons(frozen)), "frozen diagnostics unaffected by later saves");
        check(!new TableFilter(DbCaptureSettings.load(), "SITEEXCLUDED").isIncluded("QUEUEENTRY"),
                "the next capture sees the saved exclusions");
        Properties superseded = DbCaptureSettings.load();
        superseded.setProperty(DbCaptureSettings.INCLUDE_QUEUE, "obsolete-value");
        check(MonitoringScope.describe(superseded, "SITEEXCLUDED", catalog).getIncluded().isEmpty(),
                "superseded legacy fields cannot break the authoritative per-table selection");
        superseded.setProperty(DbCaptureSettings.INCLUDED_TABLES, "QUEUEENTRY");
        MonitoringScope.Catalog withoutQueue = new MonitoringScope.Catalog(Map.of("WTPART", true));
        MonitoringScope.Selection stale = MonitoringScope.describe(superseded, "", withoutQueue);
        check(stale.getIncluded().isEmpty() && stale.getNotice().contains("QUEUEENTRY")
                && stale.getNotice().contains("no longer present"), "missing physical selections are explicitly disclosed");
        expectFailure(() -> saved.getIncluded().add("X"), UnsupportedOperationException.class);
        expectFailure(() -> saved.getReasons().clear(), UnsupportedOperationException.class);
        expectFailure(() -> notRecorded.clear(), UnsupportedOperationException.class);
        preferenceExclusionPrecedence(file, catalog);
    }

    private static void preferenceExclusionPrecedence(Path file, MonitoringScope.Catalog catalog) throws Exception {
        Properties settings = DbCaptureSettings.load();
        settings.setProperty(DbCaptureSettings.EXTRA_EXCLUDES, "PREFERENCEINSTANCE,WTPART");
        DbCaptureSettings.save(settings);
        MonitoringScope.Selection blocked = MonitoringScope.describe(settings, "", catalog);
        check(blocked.getLocked().contains("PREFERENCEINSTANCE") && !blocked.getExcluded().contains("PREFERENCEINSTANCE")
                && blocked.getReasons().get("PREFERENCEINSTANCE").contains("extraExcludeTables"),
                "an existing explicit preference exclusion remains locked until deliberately removed");
        byte[] before = Files.readAllBytes(file);
        expectFailure(() -> DbCaptureSettings.updateIncludedTables("PREFERENCEINSTANCE", catalog, ""),
                IllegalArgumentException.class);
        check(java.util.Arrays.equals(before, Files.readAllBytes(file)), "preference opt-in cannot bypass an extra exclusion");
        DbCaptureSettings.updateIncludedTables("QUEUEENTRY", catalog, "");
        check(DbCaptureSettings.text(DbCaptureSettings.EXTRA_EXCLUDES).equals("PREFERENCEINSTANCE,WTPART"),
                "an unrelated scope save never removes the existing preference exclusion");

        // Simulate the authorized exact-token removal only in this test's fake wt.home.
        DbCaptureSettings.set(DbCaptureSettings.EXTRA_EXCLUDES, "WTPART");
        MonitoringScope.Selection movable = MonitoringScope.describe(DbCaptureSettings.load(), "", catalog);
        check(movable.getExcluded().contains("PREFERENCEINSTANCE") && !movable.getLocked().contains("PREFERENCEINSTANCE"),
                "removing the explicit token exposes the movable preference default");
        MonitoringScope.Selection included = DbCaptureSettings.updateIncludedTables("PREFERENCEINSTANCE", catalog, "");
        check(included.getIncluded().equals(List.of("PREFERENCEINSTANCE"))
                && DbCaptureSettings.text(DbCaptureSettings.EXTRA_EXCLUDES).equals("WTPART"),
                "preference opt-in persists while all remaining extra exclusions survive");
        TableFilter frozen = new TableFilter(DbCaptureSettings.load(), "");
        check(frozen.isIncluded("PREFERENCEINSTANCE") && !frozen.isIncluded("WTPART"),
                "only the selected preference default is re-enabled");
        before = Files.readAllBytes(file);
        for (String site : List.of("PREFERENCEINSTANCE", "PREFERENCE*", "*INSTANCE")) {
            MonitoringScope.Selection siteLocked = MonitoringScope.describe(DbCaptureSettings.load(), site, catalog);
            check(siteLocked.getLocked().contains("PREFERENCEINSTANCE")
                    && !catalog.includedTables(new TableFilter(DbCaptureSettings.load(), site)).contains("PREFERENCEINSTANCE"),
                    "exact and wildcard site exclusions still beat preference opt-in");
            expectFailure(() -> DbCaptureSettings.updateIncludedTables("PREFERENCEINSTANCE", catalog, site),
                    IllegalArgumentException.class);
            check(java.util.Arrays.equals(before, Files.readAllBytes(file)), "site-blocked preference edit remains atomic");
        }
        DbCaptureSettings.set(DbCaptureSettings.EXTRA_EXCLUDES, "WTPART,PREFERENCE*");
        before = Files.readAllBytes(file);
        expectFailure(() -> DbCaptureSettings.updateIncludedTables("PREFERENCEINSTANCE", catalog, ""),
                IllegalArgumentException.class);
        check(java.util.Arrays.equals(before, Files.readAllBytes(file)), "wildcard extra exclusion has no preference exception");
        check(frozen.isIncluded("PREFERENCEINSTANCE")
                && !new TableFilter(DbCaptureSettings.load(), "").isIncluded("PREFERENCEINSTANCE"),
                "later administrative exclusions affect only the next capture");
    }

    private interface Action { void run() throws Exception; }
    private static void expectFailure(Action action, Class<? extends Exception> type) {
        try { action.run(); throw new AssertionError("Expected " + type.getSimpleName()); }
        catch (Exception e) { check(type.isInstance(e), "correct explicit failure " + e); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
