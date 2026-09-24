package com.custom.dbcapture.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated JDBC contract checks: proxies fail on any attempted database write. */
public final class MonitoringScopeCatalogTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        AtomicInteger statementsClosed = new AtomicInteger();
        AtomicInteger rowsClosed = new AtomicInteger();
        AtomicInteger row = new AtomicInteger(-1);
        AtomicInteger queries = new AtomicInteger();
        String[] names = {"DBCAPTURESESSION", "METHODCONTEXTS", "PAGINGSESSION",
                "QUEUEENTRY", "WTPART", "BIN$UNIT$0", "QuotedTable", "PREFERENCEINSTANCE"};
        ResultSet rows = proxy(ResultSet.class, (p, method, values) -> {
            switch (method.getName()) {
                case "next": return row.incrementAndGet() < names.length;
                case "getString": return names[row.get()];
                case "getInt": return row.get() == 1 ? 0 : 1;
                case "close": rowsClosed.incrementAndGet(); return null;
                default: throw new AssertionError("Unexpected ResultSet call: " + method.getName());
            }
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (p, method, values) -> {
            switch (method.getName()) {
                case "setQueryTimeout": check((int) values[0] == 30, "catalog query has a finite timeout"); return null;
                case "setFetchSize": return null;
                case "executeQuery": queries.incrementAndGet(); return rows;
                case "close": statementsClosed.incrementAndGet(); return null;
                default: throw new AssertionError("Unexpected statement call (no writes allowed): " + method.getName());
            }
        });
        Connection connection = proxy(Connection.class, (p, method, values) -> {
            if (!method.getName().equals("prepareStatement")) {
                throw new AssertionError("Caller owns connection; no close/commit/writes: " + method.getName());
            }
            String sql = (String) values[0];
            check(sql.startsWith("SELECT ") && sql.contains("FROM USER_TABLES t"), "physical table catalog, not views");
            check(sql.contains("CASE WHEN EXISTS") && sql.contains("COLUMN_NAME='IDA2A2'"),
                    "eligibility is annotated, never filters no-ID tables out of the catalog");
            return statement;
        });
        MonitoringScope.Catalog catalog = MonitoringScope.readCatalog(connection);
        check(queries.get() == 1 && statementsClosed.get() == 1 && rowsClosed.get() == 1,
                "one metadata query closes only owned statement/result resources");
        check(catalog.getTableNames().size() == names.length, "all physical names survive");
        check(catalog.captureLimitation("UNKNOWN").contains("Not a physical"), "unknown physical table is ineligible");
        check(catalog.captureLimitation("METHODCONTEXTS").contains("No IDA2A2"), "no-ID table explicitly locked");
        check(catalog.captureLimitation("QuotedTable").contains("mixed-case"), "case-sensitive physical names not offered");
        check(catalog.captureLimitation("BIN$UNIT$0").contains("recycle-bin"), "recycle-bin tables locked");

        TableFilter filter = new TableFilter(new Properties(), "");
        check(catalog.includedTables(filter).equals(java.util.List.of("WTPART")), "engine-eligible frozen selection");
        Map<String, String> excluded = catalog.excludedReasons(filter);
        check(excluded.size() == names.length - 1 && excluded.containsKey("QuotedTable"),
                "not-recorded diagnostics contains every locked/default-excluded physical table");
        check(catalog.captureLimitation("PREFERENCEINSTANCE") == null && excluded.containsKey("PREFERENCEINSTANCE"),
                "preference instances are physically eligible but not recorded by default");
        Properties settings = new Properties();
        settings.setProperty("includedTables", "PREFERENCEINSTANCE,QUEUEENTRY");
        MonitoringScope.Selection selection = MonitoringScope.describe(settings, "", catalog);
        check(selection.getIncluded().contains("PREFERENCEINSTANCE") && !selection.getLocked().contains("PREFERENCEINSTANCE"),
                "preference opt-in is movable, not physically locked");
        check(catalog.includedTables(new TableFilter(settings, ""))
                .equals(java.util.List.of("PREFERENCEINSTANCE", "QUEUEENTRY", "WTPART")),
                "the same catalog admits preference rows only after opt-in");
        check(catalog.excludedReasons(new TableFilter(settings, "PREFERENCEINSTANCE"))
                .get("PREFERENCEINSTANCE").contains(TableFilter.EXCLUDE_PROPERTY),
                "explicit site exclusion still appears in the frozen complement");
        MonitoringScope.Selection copied = roundTrip(selection);
        check(copied.getIncluded().equals(selection.getIncluded()) && copied.getLocked().equals(selection.getLocked())
                && copied.getReasons().equals(selection.getReasons()), "scope safely crosses the method-server boundary");
        MonitoringScope.Catalog copiedCatalog = roundTrip(catalog);
        check(copiedCatalog.excludedReasons(filter).equals(excluded), "catalog can be retained as a frozen value");
        try {
            copiedCatalog.getTableNames().clear();
            throw new AssertionError("Catalog became mutable after serialization");
        } catch (UnsupportedOperationException expected) {
            assertions++;
        }
        Connection failed = proxy(Connection.class, (p, method, values) -> {
            throw new SQLException("isolated catalog unavailable");
        });
        try {
            MonitoringScope.readCatalog(failed);
            throw new AssertionError("Catalog failures must not advertise a fabricated fallback");
        } catch (SQLException expected) {
            check(expected.getMessage().equals("isolated catalog unavailable"), "catalog failure remains explicit");
        }
        String boundary = "T".repeat(40);
        String oversized = boundary + "X";
        MonitoringScope.Catalog widths = new MonitoringScope.Catalog(Map.of(boundary, true, oversized, true));
        check(widths.includedTables(filter).equals(java.util.List.of(boundary)),
                "physical eligibility respects the unchanged 40-character capture model width");
        check(widths.excludedReasons(filter).get(oversized).contains("40-character")
                && widths.getTableNames().contains(oversized),
                "overlength physical table remains visible with its exact exclusion reason");
        check(MonitoringScope.describe(new Properties(), "", widths).getLocked().equals(java.util.List.of(oversized)),
                "overlength physical tables cannot be enabled by the monitoring selector");
        System.out.println("PASS: " + assertions + " isolated scope catalog/RMI/freeze assertions; no database connection.");
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        assertions++;
    }
}
