package com.ptc.dbcapture;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TimeZone;
import java.util.TreeMap;

import com.ptc.dbcapture.engine.DatabaseTime;
import com.ptc.dbcapture.engine.IdentityResolver;

import wt.fc.QueryResult;
import wt.util.WTException;
import wt.util.WTStandardDateFormat;

/** Request-local view over the authoritative column deltas, not the optional rollup CLOB. */
public final class DbCapturePresentation {
   public static final String REQUEST_KEY = DbCapturePresentation.class.getName();
   public static final int TEXT_LIMIT = 60000;
   public static final int OBJECT_LIMIT = 50;
   private static final int LIVE_LOOKUP_LIMIT = 200;

   @FunctionalInterface
   public interface NameLookup {
      IdentityResolver.Names find(String className, long rowId);
   }

   private record Group(String captureId, String table, String operation) { }
   private record ObjectKey(String captureId, String table, long id) { }
   private record LiveKey(String className, long id) { }
   private record Label(String value, String source) { }
   private record LabelSummary(String text, String tooltip) { }
   private record TimeSelection(Timestamp time, String source) { }

   private final Map<Group, Map<Long, ObjectDetails>> groups = new HashMap<>();
   private final Map<ObjectKey, List<ObjectDetails>> objects = new HashMap<>();
   private final Map<LiveKey, IdentityResolver.Names> liveNames = new HashMap<>();
   private final Map<String, DbCaptureSession> sessions;
   private final TimeZone databaseZone;
   private final NameLookup lookup;

   public DbCapturePresentation(Collection<DbCaptureAttrDelta> deltas,
                                Map<String, DbCaptureSession> sessions,
                                TimeZone databaseZone, NameLookup lookup) {
      this.sessions = Map.copyOf(sessions);
      this.databaseZone = (TimeZone) databaseZone.clone();
      this.lookup = lookup;
      for (DbCaptureAttrDelta delta : deltas) {
         Group group = new Group(delta.getCaptureId(), delta.getTableName(), delta.getOperation());
         ObjectDetails object = groups.computeIfAbsent(group, ignored -> new TreeMap<>())
               .computeIfAbsent(delta.getTargetRowId(), ignored -> {
                  ObjectDetails created = new ObjectDetails(delta);
                  objects.computeIfAbsent(new ObjectKey(delta.getCaptureId(), delta.getTableName(),
                        delta.getTargetRowId()), key -> new ArrayList<>()).add(created);
                  return created;
               });
         object.deltas.add(delta);
      }
      for (Map<Long, ObjectDetails> group : groups.values()) {
         for (ObjectDetails object : group.values()) {
            object.deltas.sort(Comparator.comparing(DbCaptureAttrDelta::getColumnName));
         }
      }
   }

   public static DbCapturePresentation load(Collection<DbCaptureTableChange> rows) throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      Set<String> captureIds = new LinkedHashSet<>();
      for (DbCaptureTableChange row : rows) {
         if (row.getCaptureId() != null) captureIds.add(row.getCaptureId());
      }
      Map<String, DbCaptureSession> sessions = new LinkedHashMap<>();
      QueryResult found = DbCaptureHelper.findSessions();
      while (found.hasMoreElements()) {
         DbCaptureSession session = (DbCaptureSession) found.nextElement();
         if (captureIds.contains(session.getCaptureId())) sessions.put(session.getCaptureId(), session);
      }
      try {
         return new DbCapturePresentation(DbCaptureHelper.findDeltasForCaptures(captureIds),
               sessions, DatabaseTime.readZone(DbCaptureJdbc.connection()),
               new IdentityResolver()::namesFromLiveObject);
      } catch (SQLException e) {
         throw new WTException(e, "Could not read the database time zone for DB Capture results.");
      }
   }

   public String changedColumns(DbCaptureTableChange row) {
      if (!DbCaptureChange.OP_UPDATE.equals(row.getOperations())
            && !DbCaptureChange.OP_LOGICAL_DELETE.equals(row.getOperations())) {
         return "";
      }
      Collection<ObjectDetails> found = group(row).values();
      if (found.isEmpty()) {
         return "Column deltas were not recorded for this update; the capture may be partial.";
      }
      StringBuilder text = new StringBuilder();
      for (ObjectDetails object : found) {
         text.append("IDA2A2=").append(object.id).append('\n');
         for (DbCaptureAttrDelta delta : object.deltas) {
            text.append("  ").append(delta.getColumnName()).append(": ")
                  .append(value(delta.getOldValue())).append(" -> ")
                  .append(value(delta.getNewValue()));
            if (delta.isTruncated()) text.append(" [stored value truncated]");
            text.append('\n');
            if (text.length() > TEXT_LIMIT) {
               return text.substring(0, TEXT_LIMIT)
                     + "\n[Display truncated; full recorded deltas remain in DbCaptureAttrDelta.]";
            }
         }
      }
      return text.toString().stripTrailing();
   }

   public String objectNames(DbCaptureTableChange row) {
      return labels(row, true).text();
   }

   public String objectNumbers(DbCaptureTableChange row) {
      return labels(row, false).text();
   }

   public String identityTooltip(DbCaptureTableChange row, boolean name) {
      return labels(row, name).tooltip();
   }

   public String objectReferences(DbCaptureTableChange row) {
      if (group(row).isEmpty()) return "NA";
      StringJoiner text = new StringJoiner("\n");
      int count = 0;
      for (ObjectDetails object : group(row).values()) {
         if (count++ == OBJECT_LIMIT) break;
         text.add(reference(row, object));
      }
      return text.toString();
   }

   public record ObjectLink(String reference, long deltaId) { }

   public List<ObjectLink> objectLinks(DbCaptureTableChange row) {
      List<ObjectLink> links = new ArrayList<>();
      for (ObjectDetails object : group(row).values()) {
         if (links.size() == OBJECT_LIMIT) break;
         wt.fc.ObjectIdentifier oid = wt.fc.PersistenceHelper.getObjectIdentifier(object.deltas.get(0));
         if (oid == null || oid.getId() <= 0) {
            throw new IllegalStateException("The captured object entry has no persisted ID.");
         }
         links.add(new ObjectLink(reference(row, object), oid.getId()));
      }
      return links;
   }

   /** Number of distinct captured object entries represented by the Object column. */
   public int objectCount(DbCaptureTableChange row) {
      return group(row).size();
   }

   public String objectReferenceTooltip(DbCaptureTableChange row) {
      String tooltip = "Recorded class name:IDA2A2. If no class was recorded, the table name is used.";
      if (group(row).size() > OBJECT_LIMIT) {
         tooltip += " Showing the first " + OBJECT_LIMIT + " of " + group(row).size() + " objects.";
      }
      return tooltip;
   }

   private static String reference(DbCaptureTableChange row, ObjectDetails object) {
      String type = object.className;
      if (type == null || type.isBlank()) type = row.getClassName();
      if (type == null || type.isBlank()) type = object.table;
      return type + ":" + object.id;
   }

   public boolean containsCapture(String captureId) {
      return sessions.containsKey(captureId);
   }

   public boolean matches(DbCaptureTableChange row, DbCaptureSearch search) {
      if (search == null || search.matches(row.searchableText())) return true;
      DbCaptureSession capture = sessions.get(row.getCaptureId());
      if (capture != null && search.matches(capture.searchableText())) return true;
      for (ObjectDetails object : group(row).values()) {
         if (search.matches(reference(row, object).toLowerCase(Locale.ROOT))) return true;
         for (DbCaptureAttrDelta delta : object.deltas) {
            if (search.matches(delta.searchableText())) return true;
         }
      }
      for (ObjectDetails object : group(row).values()) {
         for (boolean name : new boolean[] { true, false }) {
            String actual = label(object, name).value;
            if (actual != null && !actual.isBlank() && search.matches(actual)) return true;
         }
      }
      return false;
   }

   public String changedAt(DbCaptureTableChange row, boolean first, Locale locale, TimeZone zone) {
      if (first) return format(selectTime(row, true).time(), locale, zone);
      DbCaptureSession session = sessions.get(row.getCaptureId());
      if (session == null) return "NA";
      return captureTime(session.getStartTime(), locale, zone) + " > "
            + captureTime(session.getEndTime(), locale, zone);
   }

   public String changedAtTooltip(DbCaptureTableChange row, boolean first) {
      return first ? selectTime(row, true).source()
            : "Capture start > stop in your display time zone.";
   }

   private static String captureTime(Timestamp time, Locale locale, TimeZone zone) {
      return time == null ? "NA"
            : WTStandardDateFormat.format(time, "yyyy-MM-dd HH:mm:ss", locale, zone);
   }

   private TimeSelection selectTime(DbCaptureTableChange row, boolean first) {
      DbCaptureSession session = sessions.get(row.getCaptureId());
      if (session == null) return new TimeSelection(null, "No recorded capture timing.");
      Timestamp selected = null;
      for (ObjectDetails object : group(row).values()) {
         Timestamp time = DatabaseTime.withinWindow(object.time, session.getStartTime(),
               session.getEndTime(), databaseZone);
         if (time != null && (selected == null || (first ? time.before(selected) : time.after(selected)))) {
            selected = time;
         }
      }
      if (selected == null) {
         selected = DatabaseTime.withinWindow(first ? row.getFirstChangeTime() : row.getLastChangeTime(),
               session.getStartTime(), session.getEndTime(), databaseZone);
      }
      if (selected != null) {
         return new TimeSelection(selected, "Best available recorded database time (approximate).");
      }
      Timestamp fallback = first ? session.getStartTime() : session.getEndTime();
      String source = first ? "Capture start" : "Capture end";
      if (fallback == null) {
         fallback = first ? session.getEndTime() : session.getStartTime();
         source = first ? "Capture end" : "Capture start";
      }
      return new TimeSelection(fallback, fallback == null ? "No recorded capture timing."
            : source + " used as a best-effort estimate; exact change time was not recorded.");
   }

   private LabelSummary labels(DbCaptureTableChange row, boolean name) {
      if (group(row).isEmpty()) return new LabelSummary("NA", "No recorded object values.");
      StringJoiner text = new StringJoiner("\n");
      Set<String> sources = new LinkedHashSet<>();
      int count = 0;
      for (ObjectDetails object : group(row).values()) {
         if (count++ == OBJECT_LIMIT) {
            break;
         }
         Label label = label(object, name);
         text.add(label.value == null || label.value.isBlank() ? "NA" : label.value);
         sources.add(label.source);
      }
      String tooltip = "Sources: " + String.join(", ", sources) + ".";
      if (group(row).size() > OBJECT_LIMIT) {
         tooltip += " Showing the first " + OBJECT_LIMIT + " of " + group(row).size() + " objects.";
      }
      return new LabelSummary(text.toString(), tooltip);
   }

   private Label label(ObjectDetails object, boolean name) {
      String captured = capturedLabel(object, name);
      if (captured != null) return new Label(captured, "captured");
      String masterTable = masterTable(object.table);
      String masterId = object.column("IDA3MASTERREFERENCE");
      if (masterTable != null && masterId != null && masterId.matches("[0-9]+")) {
         try {
            List<ObjectDetails> masters = objects.get(new ObjectKey(object.captureId, masterTable,
                  Long.parseLong(masterId)));
            if (masters != null && masters.size() == 1) {
               captured = capturedLabel(masters.get(0), name);
               if (captured != null) return new Label(captured, "captured master");
            }
         } catch (NumberFormatException e) {
            return new Label(null, "invalid captured master ID");
         }
      }
      if (object.className == null) return new Label(null, "no captured class");
      LiveKey key = new LiveKey(object.className, object.id);
      if (!liveNames.containsKey(key)) {
         if (liveNames.size() >= LIVE_LOOKUP_LIMIT) {
            return new Label(null, "live lookup limit reached");
         }
         liveNames.put(key, lookup.find(object.className, object.id));
      }
      IdentityResolver.Names current = liveNames.get(key);
      String value = name ? current.name() : current.number();
      return new Label(value, value == null ? "not captured or no readable current value" : "current");
   }

   private static String capturedLabel(ObjectDetails object, boolean name) {
      for (String column : name ? new String[] { "NAME", "TITLE" }
            : new String[] { "WTPARTNUMBER", "DOCUMENTNUMBER", "EPMDOCUMENTNUMBER", "NUMBER" }) {
         String value = object.column(column);
         if (value != null && !value.isBlank()) return value;
      }
      return null;
   }

   private static String masterTable(String table) {
      if ("WTPART".equals(table)) return "WTPARTMASTER";
      if ("WTDOCUMENT".equals(table)) return "WTDOCUMENTMASTER";
      if ("EPMDOCUMENT".equals(table)) return "EPMDOCUMENTMASTER";
      return null;
   }

   private Map<Long, ObjectDetails> group(DbCaptureTableChange row) {
      return groups.getOrDefault(new Group(row.getCaptureId(), row.getTableName(),
            row.getOperations()), Map.of());
   }

   private static String value(String value) {
      return value == null ? "(null)" : "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
   }

   private static String format(Timestamp time, Locale locale, TimeZone zone) {
      return time == null ? "NA"
            : WTStandardDateFormat.format(time, "yyyy-MM-dd HH:mm:ss z", locale, zone);
   }

   private static final class ObjectDetails {
      final String captureId;
      final String table;
      final String operation;
      final String className;
      final long id;
      final Timestamp time;
      final List<DbCaptureAttrDelta> deltas = new ArrayList<>();

      ObjectDetails(DbCaptureAttrDelta delta) {
         captureId = delta.getCaptureId();
         table = delta.getTableName();
         operation = delta.getOperation();
         className = delta.getClassName();
         id = delta.getTargetRowId();
         time = delta.getChangeTime();
      }

      String column(String column) {
         for (DbCaptureAttrDelta delta : deltas) {
            if (column.equals(delta.getColumnName())) {
               return DbCaptureChange.OP_DELETE.equals(operation)
                     ? delta.getOldValue() : delta.getNewValue();
            }
         }
         return null;
      }
   }
}
