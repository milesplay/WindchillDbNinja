package com.ptc.dbcapture;

import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import com.ptc.core.components.rendering.RenderingContext;
import com.ptc.core.components.rendering.guicomponents.TextArea;
import com.ptc.core.components.rendering.guicomponents.TextDisplayComponent;
import com.ptc.core.components.rendering.guicomponents.GUIComponentArray;
import com.ptc.core.components.rendering.guicomponents.UrlDisplayComponent;
import com.ptc.core.components.rendering.guicomponents.IconComponent;
import com.ptc.core.components.descriptor.ModelContext;
import com.ptc.core.components.descriptor.ComponentDescriptor;
import com.ptc.core.components.descriptor.DataUtilityHelper;
import com.ptc.dbcapture.engine.DatabaseTime;
import com.ptc.dbcapture.engine.IdentityResolver;
import com.ptc.dbcapture.mvc.DbCaptureReportDataUtility;
import com.ptc.dbcapture.mvc.DbCaptureSessionDiagnosticsDataUtility;
import com.ptc.dbcapture.mvc.builders.DbCaptureChangeTableBuilder;
import com.ptc.dbcapture.mvc.builders.DbCaptureSessionTableBuilder;
import com.ptc.mvc.components.ColumnConfig;
import com.ptc.mvc.components.ComponentConfig;
import com.ptc.mvc.components.ComponentConfigFactory;
import com.ptc.mvc.components.TableConfig;
import com.ptc.netmarkets.util.beans.NmCommandBean;

import jakarta.servlet.http.HttpServletRequest;
import wt.fc.ObjectIdentifier;
import wt.fc.PersistInfo;
import wt.query.QuerySpec;
import wt.query.SearchCondition;

public final class PresentationTest {
    private static int assertions;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone DB_ZONE = TimeZone.getTimeZone("GMT+08:00");
    private static final Timestamp START = Timestamp.from(Instant.parse("2026-09-17T23:00:00Z"));
    private static final Timestamp END = Timestamp.from(Instant.parse("2026-09-17T23:01:00Z"));
    private static final Timestamp CHANGE = Timestamp.from(Instant.parse("2026-09-17T23:00:30Z"));

    public static void main(String[] args) throws Exception {
        DbCaptureSession session = new DbCaptureSession();
        session.setCaptureId("CAP-TEST");
        session.setDescription("unique capture description");
        session.setStartedBy("report-admin");
        session.setStartTime(START);
        session.setEndTime(END);
        List<DbCaptureAttrDelta> deltas = new ArrayList<>();
        deltas.add(delta("CAP-TEST", "WTPART", "UPDATE", 100, "NAME", "old", "new <&>\nname", 10, CHANGE));
        deltas.add(delta("CAP-TEST", "WTPART", "UPDATE", 100, "VALUE", null, "", 10, CHANGE));
        deltas.add(delta("CAP-TEST", "WTPART", "CREATE", 201, "IDA3MASTERREFERENCE", null, "200", 10, CHANGE));
        deltas.add(delta("CAP-TEST", "WTPARTMASTER", "CREATE", 200, "NAME", null, "Captured Part", 10, CHANGE));
        deltas.add(delta("CAP-TEST", "WTPARTMASTER", "CREATE", 200, "WTPARTNUMBER", null, "0000200", 10, CHANGE));
        deltas.add(delta("CAP-TEST", "WTPARTMASTER", "DELETE", 300, "NAME", "Deleted Name", null, 10, CHANGE));
        deltas.add(delta("CAP-TEST", "WTPARTMASTER", "DELETE", 300, "WTPARTNUMBER", "0000300", null, 10, CHANGE));
        deltas.add(delta("CAP-TEST", "WTDOCUMENT", "LOGICAL_DELETE", 400, "MARKFORDELETEA2", "0", "1", 0, null));
        deltas.add(delta("CAP-OTHER", "WTPART", "UPDATE", 100, "OTHER_CAPTURE_COLUMN", "a", "b", 10, CHANGE));
        deltas.add(delta("CAP-TEST", "MULTIOBJECT", "CREATE", 501, "NAME", null, "one", 10, CHANGE));
        deltas.add(delta("CAP-TEST", "MULTIOBJECT", "CREATE", 502, "NAME", null, "two", 10, CHANGE));
        AtomicInteger calls = new AtomicInteger();
        DbCapturePresentation report = new DbCapturePresentation(deltas, Map.of("CAP-TEST", session),
                DB_ZONE, (type, id) -> {
                    calls.incrementAndGet();
                    return new IdentityResolver.Names("Current Name", "Current Number");
                });
        DbCaptureTableChange update = row("WTPART", "UPDATE");
        String columns = report.changedColumns(update);
        check(columns.contains("IDA2A2=100") && columns.contains("NAME: \"old\" -> \"new <&>\\nname\""),
                "UPDATE details show row, column, old/new values and escaped line breaks");
        check(columns.contains("VALUE: (null) -> \"\""), "null and empty strings remain distinct");
        check(!columns.contains("OTHER_CAPTURE_COLUMN"), "no cross-capture row mixing");
        check(report.changedColumns(row("WTPART", "CREATE")).isEmpty(), "CREATE has no changed-column display");
        check(report.changedColumns(row("WTPARTMASTER", "DELETE")).isEmpty(), "DELETE has no changed-column display");
        check(report.changedColumns(row("WTDOCUMENT", "LOGICAL_DELETE")).contains("MARKFORDELETEA2"),
                "logical delete shows its actual marker UPDATE");
        check(report.changedColumns(row("MISSING", "UPDATE")).contains("not recorded"),
                "missing stored UPDATE details are explicit");
        check(report.objectNames(update).equals("new <&>\nname"), "Name contains only the captured name");
        check(report.objectNumbers(update).equals("Current Number"), "Number contains only the current number");
        check(report.identityTooltip(update, true).contains("captured")
                        && report.identityTooltip(update, false).contains("current"),
                "identity provenance is kept in tooltips, not cell text");
        report.objectNumbers(update);
        check(calls.get() == 1, "live lookup is cached for a report");
        check(report.objectNames(row("WTPART", "CREATE")).equals("Captured Part"),
                "iteration gets name from its captured master");
        check(report.objectNumbers(row("WTPART", "CREATE")).equals("0000200"),
                "master numbers preserve leading zeroes");
        check(report.objectNames(row("WTPARTMASTER", "DELETE")).equals("Deleted Name"),
                "deleted name comes from old values without loading the deleted object");
        check(report.objectNames(row("MISSING", "UPDATE")).equals("NA")
                        && report.objectNumbers(row("MISSING", "UPDATE")).equals("NA"),
                "unavailable identity columns are exactly NA");
        check(report.objectCount(row("MULTIOBJECT", "CREATE")) == 2,
                "Rows reports the number of distinct entries represented by Object");
        check(report.matches(update, DbCaptureSearch.forKeyword("old")), "search finds recorded old value");
        check(report.matches(update, DbCaptureSearch.forKeyword("new <&>")), "search finds recorded new value");
        check(report.matches(update, DbCaptureSearch.forKeyword("unique capture description")),
                "capture description search includes its visible change rows");
        check(report.matches(update, DbCaptureSearch.forKeyword("report-admin")),
                "capture user search includes its visible change rows");
        check(report.matches(row("WTPART", "CREATE"), DbCaptureSearch.forKeyword("0000200")),
                "search finds captured master number");
        check(report.changedAt(update, false, Locale.US, DB_ZONE)
                        .equals("2026-09-18 07:00:00 > 2026-09-18 07:01:00"),
                "Changed At is the capture start-to-stop range in the user's time zone");
        check(report.changedAt(row("WTDOCUMENT", "LOGICAL_DELETE"), false, Locale.US, UTC)
                        .equals("2026-09-17 23:00:00 > 2026-09-17 23:01:00"),
                "all operations show the same recorded capture window, without a timezone suffix");
        check(report.changedAtTooltip(row("WTDOCUMENT", "LOGICAL_DELETE"), false)
                        .equals("Capture start > stop in your display time zone."),
                "tooltip describes the recorded capture window");
        check(CHANGE.equals(DatabaseTime.withinWindow(CHANGE, START, END, DB_ZONE)), "UTC timestamps unchanged");
        Timestamp oldWallTime = new Timestamp(CHANGE.getTime() + 8 * 3600000L);
        check(CHANGE.equals(DatabaseTime.withinWindow(oldWallTime, START, END, DB_ZONE)),
                "legacy database-local timestamp normalized only when it fits bounds");
        check(DatabaseTime.withinWindow(new Timestamp(CHANGE.getTime() + 24 * 3600000L),
                        START, END, DB_ZONE) == null, "unexplainable timestamps not guessed");
        limits(session);
        compactValuesAndTimes(session);
        renderer();
        dataUtility(report, update);
        columnWiring();
        tableColumns();
        navigation();
        objectReferences(session);
        sessionDiagnostics(session);
        QuerySpec query = new QuerySpec(DbCaptureAttrDelta.class);
        query.appendWhere(new SearchCondition(DbCaptureAttrDelta.class, DbCaptureAttrDelta.CAPTURE_ID,
                new String[] {"CAP-TEST", "CAP-OTHER"}, false), new int[] {0});
        check(query.toString().contains(" IN ") && !query.toString().contains("NOT IN"),
                "capture query uses an inclusive IN filter");
        System.out.println("PASS: " + assertions + " presentation assertions.");
    }

    private static void objectReferences(DbCaptureSession session) throws Exception {
        DbCaptureAttrDelta part = delta("CAP-TEST", "MANUFACTURERPART", "CREATE",
                889561, "IDA2A2", null, "889561", 0, null);
        part.setClassName("com.ptc.windchill.suma.part.ManufacturerPart");
        DbCaptureAttrDelta master = delta("CAP-TEST", "MANUFACTURERPARTMASTER", "CREATE",
                889558, "NAME", null, "0000000161", 0, null);
        master.setClassName("com.ptc.windchill.suma.part.ManufacturerPartMaster");
        DbCaptureAttrDelta branch = delta("CAP-TEST", "CONTROLBRANCH", "DELETE",
                889562, "IDA2A2", "889562", null, 0, null);
        branch.setClassName("wt.vc.ControlBranch");
        DbCaptureAttrDelta large = delta("CAP-TEST", "CONTROLBRANCH", "DELETE",
                9007199254740993L, "IDA2A2", "9007199254740993", null, 0, null);
        large.setClassName("wt.vc.ControlBranch");
        DbCapturePresentation report = new DbCapturePresentation(List.of(part, master, branch, large),
                Map.of("CAP-TEST", session), DB_ZONE, (type, id) -> {
                    throw new AssertionError("Object references must not require current-object lookups");
                });
        check(report.objectReferences(row("MANUFACTURERPART", "CREATE"))
                        .equals("com.ptc.windchill.suma.part.ManufacturerPart:889561"),
                "part reference has its recorded class and row ID");
        check(report.objectReferences(row("MANUFACTURERPARTMASTER", "CREATE"))
                        .equals("com.ptc.windchill.suma.part.ManufacturerPartMaster:889558"),
                "master reference is not replaced by its business number");
        check(report.objectReferences(row("CONTROLBRANCH", "DELETE"))
                        .equals("wt.vc.ControlBranch:889562\nwt.vc.ControlBranch:9007199254740993"),
                "class-only and deleted rows have exact IDs, including IDs beyond JS integer precision");
        check(report.matches(row("MANUFACTURERPARTMASTER", "CREATE"), DbCaptureSearch.forKeyword(
                        "com.ptc.windchill.suma.part.ManufacturerPartMaster:889558")),
                "formatted object references are searchable");
        check(report.objectReferences(row("MISSING", "CREATE")).equals("NA"),
                "missing captured row IDs are not invented");
        part.setClassName(null);
        DbCapturePresentation missingClass = new DbCapturePresentation(List.of(part),
                Map.of("CAP-TEST", session), DB_ZONE, (type, id) -> {
                    throw new AssertionError("Missing class must not cause a live lookup");
                });
        DbCaptureTableChange recordedType = row("MANUFACTURERPART", "CREATE");
        recordedType.setClassName("com.ptc.windchill.suma.part.ManufacturerPart");
        check(missingClass.objectReferences(recordedType)
                        .equals("com.ptc.windchill.suma.part.ManufacturerPart:889561"),
                "recorded rollup class is used when the delta class is missing");
        check(missingClass.objectReferences(row("MANUFACTURERPART", "CREATE")).equals("MANUFACTURERPART:889561"),
                "unknown class uses an explicit table:row reference instead of guessing a Java type");
    }

    private static void dataUtility(DbCapturePresentation report, DbCaptureTableChange row) throws Exception {
        row.setPersistInfo(PersistInfo.newPersistInfo(
                ObjectIdentifier.newObjectIdentifier(DbCaptureTableChange.class, 1001L)));
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                PresentationTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())
                            && DbCapturePresentation.REQUEST_KEY.equals(args[0])) return report;
                    if ("getParameterNames".equals(method.getName())
                            || "getAttributeNames".equals(method.getName())
                            || "getHeaderNames".equals(method.getName())) return java.util.Collections.emptyEnumeration();
                    if ("getParameterMap".equals(method.getName())) return Map.of();
                    return null;
                });
        NmCommandBean command = new NmCommandBean();
        command.setRequest(request);
        ModelContext context = (ModelContext) Proxy.newProxyInstance(PresentationTest.class.getClassLoader(),
                new Class<?>[] {ModelContext.class}, (proxy, method, args) -> {
                    if ("getNmCommandBean".equals(method.getName())) return command;
                    if ("getModelData".equals(method.getName())) return null;
                    if ("getLocale".equals(method.getName())) return Locale.US;
                    return null;
                });
        DbCaptureReportDataUtility utility = new DbCaptureReportDataUtility();
        utility.setModelData("details", List.of(), context);
        utility.setModelData("details", List.of(row), context);
        Object details = utility.getDataValue("details", row, context);
        check(details instanceof TextArea && ((TextArea) details).getValue().contains("NAME:"),
                "async DataUtility lifecycle with null model-data map renders UPDATE textarea");
        check(report.containsCapture("CAP-TEST") && !report.containsCapture("CAP-NOT-LOADED"),
                "request cache checks capture coverage rather than reusing unrelated results");
        TextDisplayComponent name = (TextDisplayComponent) utility.getDataValue("dbcObjectName", row, context);
        check(!name.getPlainTextValue().contains("IDA2A2=")
                        && !name.getPlainTextValue().contains("(captured)")
                        && name.getTooltip().contains("captured"),
                "DataUtility renders a plain name with provenance on hover");
        GUIComponentArray objects = (GUIComponentArray) utility.getDataValue("objectIdentities", row, context);
        UrlDisplayComponent object = (UrlDisplayComponent) objects.get(0);
        check(object.getLabelForTheLink().equals("wt.part.WTPart:100")
                        && object.getLink().contains("objectDetails.jsp?entry=")
                        && "_blank".equals(object.getTarget()),
                "Object DataUtility links recorded class:ID to a new current-data page");
        TextDisplayComponent objectCount =
                (TextDisplayComponent) utility.getDataValue("dbcObjectCount", row, context);
        check(objectCount.getPlainTextValue().equals("1")
                        && objectCount.getTooltip().contains("Object column"),
                "Rows DataUtility renders the Object-entry count with an explicit tooltip");
        TextDisplayComponent time = (TextDisplayComponent) utility.getDataValue("lastChangeTime", row, context);
        check(time.getPlainTextValue().matches(
                        "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} &gt; \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "DataUtility HTML-escapes the requested start > stop text: " + time.getPlainTextValue());
        check(time.getTooltip().contains("Capture start") && time.getTooltip().contains("stop"),
                "capture-period tooltip: " + time.getTooltip());
    }

    private static void tableColumns() throws Exception {
        List<ComponentConfig> columns = new ArrayList<>();
        TableConfig table = (TableConfig) Proxy.newProxyInstance(PresentationTest.class.getClassLoader(),
                new Class<?>[] {TableConfig.class}, (proxy, method, args) -> {
                    if ("addComponent".equals(method.getName())) columns.add((ComponentConfig) args[0]);
                    if ("getComponents".equals(method.getName())) return columns;
                    return null;
                });
        ComponentConfigFactory factory = (ComponentConfigFactory) Proxy.newProxyInstance(
                PresentationTest.class.getClassLoader(), new Class<?>[] {ComponentConfigFactory.class},
                (proxy, method, args) -> {
                    if ("newTableConfig".equals(method.getName())) return table;
                    if ("newColumnConfig".equals(method.getName())) {
                        String id = (String) args[0];
                        return Proxy.newProxyInstance(PresentationTest.class.getClassLoader(),
                                new Class<?>[] {ColumnConfig.class}, (column, call, values) ->
                                        "getId".equals(call.getName()) ? id : null);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DbCaptureChangeTableBuilder builder = new DbCaptureChangeTableBuilder();
        builder.setComponentConfigFactory(factory);
        builder.buildComponentConfig(null);
        List<String> ids = columns.stream().map(ComponentConfig::getId).toList();
        check(ids.contains("dbcObjectCount") && !ids.contains("rowsAffected"),
                "Rows is restored as the Object-entry count without exposing the old stored tally");
        check(ids.containsAll(List.of("details", "lastChangeTime", "dbcObjectName", "dbcObjectNumber")),
                "requested detail, time and identity columns remain");
        columns.clear();
        DbCaptureSessionTableBuilder sessions = new DbCaptureSessionTableBuilder();
        sessions.setComponentConfigFactory(factory);
        sessions.buildComponentConfig(null);
        ids = columns.stream().map(ComponentConfig::getId).toList();
        check(ids.containsAll(List.of("status", "captureMode", "warnings")),
                "Status, Mode and Warnings remain available by default");
        check(!ids.contains("errorText"), "Error is removed from Capture Sessions and its column chooser");
        check(ids.contains("dbcCaptureSql") && !ids.contains("dbcCaptureStack"),
                "one combined SQL / Stack Trace column, including the column chooser");
    }

    private static void navigation() throws Exception {
        String target = DbCaptureNavigation.resultsUrl("https://example.invalid/Windchill/app/",
                "OR:wt.inf.container.ExchangeContainer:5", "CAP-000123");
        java.net.URI uri = java.net.URI.create(target);
        check(uri.getPath().equals("/Windchill/app/") && uri.getQuery().equals("dbcResults=CAP-000123"),
                "result link refreshes the Windchill shell and cached action state");
        check(uri.getFragment().startsWith("ptc1/dbcapture/dbCaptureAdmin?ContainerOid=OR:wt.inf.container.ExchangeContainer:5")
                        && uri.getFragment().endsWith("dbcCompletedCapture=CAP-000123"),
                "result link uses Site context and targets the completed capture");
        check(DbCaptureNavigation.redirectScript(target).startsWith("(window.top || window).location.assign("),
                "Quick Links Stop navigates the shell rather than a result iframe");
        for (String invalid : List.of("", "CAP-1&evil=true", "<script>", "CAP-X")) {
            try {
                DbCaptureNavigation.resultsUrl("https://example.invalid/Windchill/app/", "site", invalid);
                throw new AssertionError("Invalid capture ID accepted");
            } catch (IllegalArgumentException expected) {
                check(true, "invalid capture ID rejected");
            }
        }
    }

    private static void sessionDiagnostics(DbCaptureSession session) throws Exception {
        session.setPersistInfo(PersistInfo.newPersistInfo(
                ObjectIdentifier.newObjectIdentifier(DbCaptureSession.class, 123456L)));
        DbCaptureSessionDiagnosticsDataUtility utility = new DbCaptureSessionDiagnosticsDataUtility();
        for (String id : List.of("dbcCaptureSql", "dbcCaptureStack")) {
            UrlDisplayComponent link = (UrlDisplayComponent) utility.getDataValue(id, session, null);
            check(link.getLink().contains("capture=123456") && "_blank".equals(link.getTarget()),
                    "diagnostic link uses the persistent ID and a new window");
            check(!link.getLink().contains("view=") && link.getLabelForTheLink().equals("SQL / Stack Trace"),
                    "current and legacy column selectors point to one combined diagnostic page");
            check(!link.getLink().contains(session.getCaptureId()), "capture-ID reuse cannot mix old diagnostics");
        }
        session.setStatus(DbCaptureSession.STATUS_COMPLETED);
        GUIComponentArray cleanStatus = (GUIComponentArray) utility.getDataValue("status", session, null);
        IconComponent clean = (IconComponent) cleanStatus.get(0);
        check(clean.getSrc().endsWith("/green.gif") && clean.getTooltip().contains("Complete"),
                "clean completion uses a green native icon and accessible description");
        TextDisplayComponent statusLabel = (TextDisplayComponent) cleanStatus.get(1);
        check(statusLabel.getPlainTextValue().equals("Complete")
                        && statusLabel.getStyleClasses().contains("dbcStatusLabel"),
                "status also has an assistive label rather than depending on color alone");
        session.setStatus(DbCaptureSession.STATUS_COMPLETED_WARNINGS);
        session.setWarnings("Request SQL/stack evidence: COMPLETE. unfinished=0, over limit=0, truncated stacks=2.");
        IconComponent notice = (IconComponent) ((GUIComponentArray) utility.getDataValue("status", session, null)).get(0);
        TextArea warnings = (TextArea) utility.getDataValue("warnings", session, null);
        check(notice.getSrc().endsWith("/yellow.gif"), "usable results with coverage notice are yellow");
        check(warnings.getHeight() == 3 && warnings.getValue().lines().count() == 3 && warnings.isReadOnly(),
                "Warnings is a read-only three-line cause / impact / action summary");
        session.setErrorText("Collection failed.");
        IconComponent failed = (IconComponent) ((GUIComponentArray) utility.getDataValue("status", session, null)).get(0);
        check(failed.getSrc().endsWith("/red.gif"), "a saved error cannot appear green or yellow");
        TextArea failedWarnings = (TextArea) utility.getDataValue("warnings", session, null);
        check(failedWarnings.getValue().contains("Collection failed."),
                "saved error remains available in Warnings without a separate Error column");
        session.setWarnings(null);
        session.setErrorText(null);
        try {
            utility.getDataValue("unknown", session, null);
            throw new AssertionError("Unknown diagnostic mode accepted");
        } catch (wt.util.WTException expected) {
            check(true, "unknown diagnostic column is explicitly refused");
        }
    }

    private static void columnWiring() throws Exception {
        java.lang.reflect.Method configure = DbCaptureChangeTableBuilder.class
                .getDeclaredMethod("configureReportColumn", ColumnConfig.class, String.class);
        configure.setAccessible(true);
        for (String columnId : List.of("objectIdentities", "details", "dbcObjectName", "dbcObjectNumber",
                "lastChangeTime", "firstChangeTime")) {
            List<String> selectors = new ArrayList<>();
            ColumnConfig column = (ColumnConfig) Proxy.newProxyInstance(PresentationTest.class.getClassLoader(),
                    new Class<?>[] {ColumnConfig.class}, (proxy, method, args) -> {
                        if ("setDataUtilityId".equals(method.getName())) selectors.add((String) args[0]);
                        return null;
                    });
            configure.invoke(null, column, columnId);
            check(selectors.equals(List.of("dbCaptureReport")), columnId + " explicitly selects its DataUtility");
        }
        if (Boolean.getBoolean("dbc.verifyInstalledRegistration")) {
            ComponentDescriptor descriptor = (ComponentDescriptor) Proxy.newProxyInstance(
                    PresentationTest.class.getClassLoader(), new Class<?>[] {ComponentDescriptor.class},
                    (proxy, method, args) -> {
                        if ("getId".equals(method.getName())) return "details";
                        if ("getProperty".equals(method.getName()) && "dataUtilityId".equals(args[0])) {
                            return "dbCaptureReport";
                        }
                        return null;
                    });
            check(DataUtilityHelper.getDataUtility(descriptor) instanceof DbCaptureReportDataUtility,
                    "installed JCA lookup resolves the explicit unique selector");
        }
    }

    private static void limits(DbCaptureSession session) throws Exception {
        List<DbCaptureAttrDelta> deltas = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            deltas.add(delta("CAP-TEST", "WTPART", "UPDATE", i + 1, "NAME",
                    "x".repeat(2000), "y".repeat(2000) + "SEARCH_TAIL", 10, CHANGE));
        }
        DbCapturePresentation report = new DbCapturePresentation(deltas, Map.of("CAP-TEST", session),
                DB_ZONE, (type, id) -> new IdentityResolver.Names(null, null));
        String text = report.changedColumns(row("WTPART", "UPDATE"));
        check(text.contains("[Display truncated") && text.length() < DbCapturePresentation.TEXT_LIMIT + 150,
                "large displayed details are bounded and disclose truncation");
        check(report.matches(row("WTPART", "UPDATE"), DbCaptureSearch.forKeyword("search_tail")),
                "search is not limited to the displayed prefix");
        check(report.objectNames(row("WTPART", "UPDATE")).lines().count() == 50
                        && !report.objectNames(row("WTPART", "UPDATE")).contains("Showing")
                        && report.identityTooltip(row("WTPART", "UPDATE"), true).contains("first 50"),
                "many-object labels contain values only; limit notice is on hover");
        check(report.objectReferences(row("WTPART", "UPDATE")).lines().count() == 50
                        && report.objectReferenceTooltip(row("WTPART", "UPDATE")).contains("first 50"),
                "multi-object reference display is bounded and explains the limit on hover");
        check(report.matches(row("WTPART", "UPDATE"), DbCaptureSearch.forKeyword("wt.part.WTPart:70")),
                "reference search includes captured rows beyond the displayed prefix");
    }

    private static void compactValuesAndTimes(DbCaptureSession session) throws Exception {
        List<DbCaptureAttrDelta> deltas = List.of(
                delta("CAP-TEST", "LINK", "UPDATE", 1, "VALUE", "a", "b", 0, null),
                delta("CAP-TEST", "MIXED", "UPDATE", 10, "NAME", "old", "First", 0, CHANGE),
                delta("CAP-TEST", "MIXED", "UPDATE", 11, "NAME", "old", "Second", 0, null));
        DbCapturePresentation report = new DbCapturePresentation(deltas, Map.of("CAP-TEST", session),
                DB_ZONE, (type, id) -> new IdentityResolver.Names(null, "  "));
        check(report.objectNames(row("LINK", "UPDATE")).equals("NA")
                        && report.objectNumbers(row("LINK", "UPDATE")).equals("NA"),
                "null and blank current identity values become NA");
        check(!report.matches(row("LINK", "UPDATE"), DbCaptureSearch.forKeyword("NA")),
                "synthetic NA display placeholders are not keyword matches");
        check(report.objectNames(row("MIXED", "UPDATE")).equals("First\nSecond"),
                "multiple objects display one plain name per line, without IDs");
        check(report.changedAt(row("MIXED", "UPDATE"), false, Locale.US, UTC)
                        .equals("2026-09-17 23:00:00 > 2026-09-17 23:01:00"),
                "row-level timestamp differences do not change the capture period");
        DbCaptureTableChange aggregate = row("AGGREGATE", "UPDATE");
        aggregate.setLastChangeTime(new Timestamp(CHANGE.getTime() + 8 * 3600000L));
        check(report.changedAt(aggregate, false, Locale.US, UTC)
                        .equals("2026-09-17 23:00:00 > 2026-09-17 23:01:00"),
                "legacy rollup time is not substituted for the requested capture period");
        check(report.changedAt(row("LINK", "UPDATE"), true, Locale.US, UTC)
                        .equals("2026-09-17 23:00:00 UTC"),
                "first-time fallback uses capture start");
        DbCapturePresentation unknown = new DbCapturePresentation(List.of(), Map.of(),
                DB_ZONE, (type, id) -> new IdentityResolver.Names(null, null));
        check(unknown.changedAt(row("LINK", "UPDATE"), false, Locale.US, UTC).equals("NA"),
                "missing capture timing displays NA");
        DbCaptureSession unfinished = new DbCaptureSession();
        unfinished.setCaptureId("CAP-TEST");
        unfinished.setStartTime(START);
        DbCapturePresentation running = new DbCapturePresentation(deltas, Map.of("CAP-TEST", unfinished),
                DB_ZONE, (type, id) -> new IdentityResolver.Names(null, null));
        check(running.changedAt(row("LINK", "UPDATE"), false, Locale.US, UTC)
                        .equals("2026-09-17 23:00:00 > NA"),
                "an unfinished capture never fabricates a Stop timestamp");
        DbCaptureSession overnight = new DbCaptureSession();
        overnight.setCaptureId("CAP-TEST");
        overnight.setStartTime(Timestamp.from(Instant.parse("2026-09-30T15:59:58Z")));
        overnight.setEndTime(Timestamp.from(Instant.parse("2026-09-30T16:00:02Z")));
        DbCapturePresentation midnight = new DbCapturePresentation(List.of(),
                Map.of("CAP-TEST", overnight), DB_ZONE, (type, id) -> new IdentityResolver.Names(null, null));
        check(midnight.changedAt(row("LINK", "UPDATE"), false, Locale.US, DB_ZONE)
                        .equals("2026-09-30 23:59:58 > 2026-10-01 00:00:02"),
                "capture period preserves seconds and local date/month boundaries");
    }

    private static void renderer() throws Exception {
        String value = "column: old -> new\n".repeat(30) + "</textarea><script>alert(1)</script>&";
        TextArea area = DbCaptureReportDataUtility.scrollableText("dbc-test", value);
        check(area.isReadOnly() && area.isEnabled() && !area.isRichText(), "renderer is read-only plain text");
        check(area.getHeight() == 5 && area.getStyleClasses().contains("dbcRecordedText"),
                "textarea has bounded height and scroll styling");
        StringWriter html = new StringWriter();
        area.draw(html, new RenderingContext());
        String output = html.toString();
        check(output.contains("<textarea") && output.contains("readonly"), "real PTC renderer emits readonly textarea");
        check(!output.contains("<script>alert(1)") && output.contains("&lt;/textarea&gt;"),
                "text cannot escape the textarea or execute markup");
        check(output.contains("column: old"), "real renderer retains content");
    }

    private static DbCaptureAttrDelta delta(String capture, String table, String operation, long id,
            String column, String before, String after, long scn, Timestamp time) throws Exception {
        DbCaptureAttrDelta delta = new DbCaptureAttrDelta();
        delta.setCaptureId(capture);
        delta.setTableName(table);
        delta.setOperation(operation);
        delta.setTargetRowId(id);
        delta.setColumnName(column);
        delta.setOldValue(before);
        delta.setNewValue(after);
        delta.setChangeScn(scn);
        delta.setChangeTime(time);
        delta.setClassName("wt.part.WTPart");
        delta.setPersistInfo(PersistInfo.newPersistInfo(ObjectIdentifier.newObjectIdentifier(
                DbCaptureAttrDelta.class, 100000L + id % 100000L)));
        return delta;
    }

    private static DbCaptureTableChange row(String table, String operation) throws Exception {
        DbCaptureTableChange row = new DbCaptureTableChange();
        row.setCaptureId("CAP-TEST");
        row.setTableName(table);
        row.setOperations(operation);
        row.setRowsAffected(1);
        return row;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
