package com.custom.dbcapture;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public final class ObjectPresentationTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        DbCaptureObjectReader.Snapshot current = new DbCaptureObjectReader.Snapshot(
                "WTPART", 888855, "wt.part.WTPart:888855", new Timestamp(0),
                List.of("IDA2A2", "NAME", "VALUE"), List.of(List.of("888855", "live now", "newer")),
                false, null, List.of());
        DbCaptureAttrDelta name = delta("NAME", "before", "after", false);
        DbCaptureAttrDelta value = delta("VALUE", null, "", false);
        DbCaptureObjectReader.Snapshot update = DbCaptureObjectReader.withCapture(current, "UPDATE", List.of(name, value));
        check(update.operationClass().equals("object-updated"), "UPDATE selects red heading class");
        check(update.rows().get(0).get(1).equals("live now"), "current row is not overwritten by captured values");
        check(update.changeFor("NAME").before().equals("before") && update.changeFor("name").after().equals("after"),
                "captured old/new values are associated with the same column case-insensitively");
        check(update.changeFor("IDA2A2") == null, "unchanged columns have no comparison block");
        check(update.changeFor("VALUE").before() == null && update.changeFor("VALUE").after().isEmpty(),
                "captured null-to-empty transition is preserved");
        check(DbCaptureObjectReader.displayValue(null).equals("(null)")
                        && DbCaptureObjectReader.displayValue("").equals("(empty)"),
                "null and empty render differently");
        for (String operation : List.of("CREATE", "DELETE", "LOGICAL_DELETE", "UNKNOWN")) {
            DbCaptureObjectReader.Snapshot snapshot = DbCaptureObjectReader.withCapture(current, operation, List.of(name));
            String expected = operation.equals("CREATE") ? "object-created"
                    : operation.equals("UNKNOWN") ? "object-unknown" : "object-deleted";
            check(snapshot.operationClass().equals(expected), "operation heading class: " + operation);
            check(snapshot.changes().isEmpty(), "only UPDATE shows captured comparison: " + operation);
        }
        DbCaptureObjectReader.Snapshot deleted = new DbCaptureObjectReader.Snapshot(
                current.table(), current.rowId(), current.reference(), current.readAt(), current.columns(),
                List.of(), false, "DELETE", List.of());
        check(deleted.rows().isEmpty() && deleted.operationClass().equals("object-deleted"),
                "deleted rows retain deletion styling even with no current record");
        DbCaptureAttrDelta longValue = delta("NAME", "x".repeat(4000), "<script>&new", true);
        DbCaptureObjectReader.ChangedValue preview = DbCaptureObjectReader
                .withCapture(current, "UPDATE", List.of(longValue)).changeFor("NAME");
        check(preview.before().length() == DbCaptureObjectReader.VALUE_LIMIT && preview.truncated(),
                "long captured values are bounded and truncation is disclosed");
        check(preview.after().equals("<script>&new"), "raw captured text is not altered before HTML escaping");
        DbCaptureAttrDelta wrong = delta("NAME", "x", "y", false);
        wrong.setTargetRowId(99);
        try {
            DbCaptureObjectReader.withCapture(current, "UPDATE", List.of(wrong));
            throw new AssertionError("A different object's deltas were accepted");
        } catch (IllegalArgumentException expected) { assertions++; }
        List<DbCaptureAttrDelta> mutable = new ArrayList<>(List.of(name));
        DbCaptureObjectReader.Snapshot frozen = DbCaptureObjectReader.withCapture(current, "UPDATE", mutable);
        mutable.clear();
        check(frozen.changes().size() == 1, "comparison DTO does not retain a mutable source list");
        System.out.println("PASS: " + assertions + " object-heading/value-comparison assertions.");
    }

    private static DbCaptureAttrDelta delta(String column, String oldValue, String newValue, boolean truncated)
            throws Exception {
        DbCaptureAttrDelta delta = new DbCaptureAttrDelta();
        delta.setTableName("WTPART");
        delta.setTargetRowId(888855);
        delta.setOperation("UPDATE");
        delta.setColumnName(column);
        delta.setOldValue(oldValue);
        delta.setNewValue(newValue);
        delta.setTruncated(truncated);
        return delta;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
