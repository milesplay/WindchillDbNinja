package com.custom.dbcapture;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.custom.dbcapture.engine.CapturedChange;
import com.custom.dbcapture.engine.CorrelationHit;
import com.custom.dbcapture.engine.MonitoringScope;

import wt.fc.Persistable;
import wt.fc.ObjectIdentifier;
import wt.fc.PersistenceHelper;
import wt.fc.QueryResult;
import wt.query.OrderBy;
import wt.query.QuerySpec;
import wt.query.SearchCondition;
import wt.services.ServiceFactory;
import wt.util.WTException;
import wt.util.WTPropertyVetoException;

/** Queries and persistence for DB capture records. */
public final class DbCaptureHelper {

   public static final DbCaptureService service =
         (DbCaptureService) ServiceFactory.getService(DbCaptureService.class);

   private DbCaptureHelper() {
   }

   /** The single running session, or null. */
   public static DbCaptureSession findRunningSession() throws WTException {
      QuerySpec qs = new QuerySpec(DbCaptureSession.class);
      qs.appendWhere(new SearchCondition(DbCaptureSession.class,
            DbCaptureSession.STATUS, SearchCondition.EQUAL,
            DbCaptureSession.STATUS_RUNNING), new int[] { 0 });
      qs.appendOrderBy(new OrderBy(
            new wt.query.ClassAttribute(DbCaptureSession.class,
                  DbCaptureSession.START_TIME), true));

      QueryResult qr = PersistenceHelper.manager.find(qs);
      return qr.hasMoreElements() ? (DbCaptureSession) qr.nextElement() : null;
   }

   /** One session by its capture id. */
   public static DbCaptureSession findSession(String captureId) throws WTException {
      if (captureId == null) {
         return null;
      }
      QuerySpec qs = new QuerySpec(DbCaptureSession.class);
      qs.appendWhere(new SearchCondition(DbCaptureSession.class,
            DbCaptureSession.CAPTURE_ID, SearchCondition.EQUAL, captureId),
            new int[] { 0 });
      QueryResult qr = PersistenceHelper.manager.find(qs);
      return qr.hasMoreElements() ? (DbCaptureSession) qr.nextElement() : null;
   }

   /** One session by its immutable persistence ID. */
   public static DbCaptureSession findSession(long sessionId) throws WTException {
      if (sessionId <= 0) {
         return null;
      }
      Persistable found = PersistenceHelper.manager.refresh(
            ObjectIdentifier.newObjectIdentifier(DbCaptureSession.class, sessionId));
      return found instanceof DbCaptureSession ? (DbCaptureSession) found : null;
   }

   /** Sessions newest first, for the administration table. */
   public static QueryResult findSessions() throws WTException {
      QuerySpec qs = new QuerySpec(DbCaptureSession.class);
      qs.appendOrderBy(new OrderBy(
            new wt.query.ClassAttribute(DbCaptureSession.class,
                  DbCaptureSession.START_TIME), true));
      return PersistenceHelper.manager.find(qs);
   }

   /**
    * True when the results page should list this session.
    *
    * An unfinished capture has nothing to read yet and a failed one has only
    * part of the picture, so by default neither appears. They are not hidden
    * outright - the page has a box to bring them back - because a session that
    * cannot be listed also cannot be read or deleted, and a tool that
    * accumulates undeletable rows is its own problem.
    */
   public static boolean isVisible(DbCaptureSession session, boolean includeUnfinished) {
      if (session == null) {
         return false;
      }
      return includeUnfinished
            || DbCaptureSession.STATUS_COMPLETED.equals(session.getStatus())
            || DbCaptureSession.STATUS_COMPLETED_WARNINGS.equals(session.getStatus());
   }

   public static String completionMessage(DbCaptureSession session) {
      boolean warnings = session.getWarnings() != null && !session.getWarnings().trim().isEmpty();
      return "DB capture " + session.getCaptureId()
            + (warnings ? " completed with warnings (results may be partial): " : " completed: ")
            + session.getCreatedCount() + " created, "
            + session.getUpdatedCount() + " updated, "
            + session.getDeletedCount() + " deleted, "
            + session.getLogicalDeletedCount() + " logically deleted across "
            + session.getTablesChanged() + " tables."
            + (warnings ? " Read Warnings in Capture Sessions before using these results." : "");
   }

   /**
    * Capture ids of the sessions the page is currently listing.
    *
    * The changes table filters on this so that the two tables cannot disagree
    * about which captures exist - and, as a side effect, so that a rollup row
    * orphaned by an earlier delete cannot appear as a change with no session.
    */
   public static Set<String> visibleCaptureIds(boolean includeUnfinished)
         throws WTException {
      Set<String> ids = new LinkedHashSet<String>();
      QueryResult qr = findSessions();
      while (qr.hasMoreElements()) {
         DbCaptureSession session = (DbCaptureSession) qr.nextElement();
         if (isVisible(session, includeUnfinished) && session.getCaptureId() != null) {
            ids.add(session.getCaptureId());
         }
      }
      return ids;
   }

   /**
    * The distinct table names a capture actually touched, upper cased and
    * sorted. This is the vocabulary the hide-tables box validates against, so
    * that a typo is reported rather than silently hiding nothing.
    */
   public static List<String> capturedTableNames(Set<String> visibleCaptureIds,
                                                 String captureId)
         throws WTException {
      Set<String> names = new TreeSet<String>();
      QueryResult qr = findTableChanges();
      while (qr.hasMoreElements()) {
         DbCaptureTableChange row = (DbCaptureTableChange) qr.nextElement();
         if (visibleCaptureIds != null
               && !visibleCaptureIds.contains(row.getCaptureId())) {
            continue;
         }
         if (captureId != null && !captureId.equals(row.getCaptureId())) {
            continue;
         }
         if (row.getTableName() != null) {
            names.add(row.getTableName().toUpperCase());
         }
      }
      return new ArrayList<String>(names);
   }

   /** Every change recorded by one session. */
   public static QueryResult findChanges(DbCaptureSession session) throws WTException {
      QuerySpec qs = new QuerySpec(DbCaptureChange.class);
      qs.appendWhere(new SearchCondition(DbCaptureChange.class,
            DbCaptureChange.SESSION_REFERENCE + ".key.id", SearchCondition.EQUAL,
            PersistenceHelper.getObjectIdentifier(session).getId()), new int[] { 0 });
      qs.appendOrderBy(new OrderBy(
            new wt.query.ClassAttribute(DbCaptureChange.class,
                  DbCaptureChange.TABLE_NAME), false));
      return PersistenceHelper.manager.find(qs);
   }

   /**
    * Every recorded column change, newest first.
    *
    * Filtering happens in the caller rather than in SQL: the keyword spans a
    * dozen columns including old and new values, and a capture holds at most a
    * few thousand rows, so a single ordered read is simpler and behaves the
    * same as the UI's own find-in-table.
    */
   public static QueryResult findAllDeltas() throws WTException {
      QuerySpec qs = new QuerySpec(DbCaptureAttrDelta.class);
      qs.appendOrderBy(new OrderBy(
            new wt.query.ClassAttribute(DbCaptureAttrDelta.class,
                  DbCaptureAttrDelta.CHANGE_SCN), true));
      return PersistenceHelper.manager.find(qs);
   }

   public static List<DbCaptureAttrDelta> findDeltasForCaptures(Set<String> captureIds)
         throws WTException {
      List<DbCaptureAttrDelta> out = new ArrayList<>();
      List<String> ids = new ArrayList<>(captureIds);
      for (int from = 0; from < ids.size(); from += 500) {
         String[] batch = ids.subList(from, Math.min(from + 500, ids.size())).toArray(new String[0]);
         QuerySpec query = new QuerySpec(DbCaptureAttrDelta.class);
         query.appendWhere(new SearchCondition(DbCaptureAttrDelta.class,
               DbCaptureAttrDelta.CAPTURE_ID, batch, false), new int[] { 0 });
         QueryResult found = PersistenceHelper.manager.find(query);
         while (found.hasMoreElements()) out.add((DbCaptureAttrDelta) found.nextElement());
      }
      return out;
   }

   /** The per column old/new values of one change. */
   public static QueryResult findDeltas(DbCaptureChange change) throws WTException {
      QuerySpec qs = new QuerySpec(DbCaptureAttrDelta.class);
      qs.appendWhere(new SearchCondition(DbCaptureAttrDelta.class,
            DbCaptureAttrDelta.CHANGE_REFERENCE + ".key.id", SearchCondition.EQUAL,
            PersistenceHelper.getObjectIdentifier(change).getId()), new int[] { 0 });
      qs.appendOrderBy(new OrderBy(
            new wt.query.ClassAttribute(DbCaptureAttrDelta.class,
                  DbCaptureAttrDelta.COLUMN_NAME), false));
      return PersistenceHelper.manager.find(qs);
   }

   /** Next capture id, taking the highest existing one and adding to it. */
   public static String nextCaptureId() throws WTException {
      QuerySpec qs = new QuerySpec(DbCaptureSession.class);
      qs.appendOrderBy(new OrderBy(
            new wt.query.ClassAttribute(DbCaptureSession.class,
                  DbCaptureSession.CAPTURE_ID), true));
      QueryResult qr = PersistenceHelper.manager.find(qs);
      return nextCaptureId(qr);
   }

   static String nextCaptureId(Enumeration<?> sessions) throws WTException {
      BigInteger highest = BigInteger.ZERO;
      while (sessions.hasMoreElements()) {
         DbCaptureSession session = (DbCaptureSession) sessions.nextElement();
         highest = highest.max(parseSuffix(session.getCaptureId()));
      }
      // Lexical ordering ceases to be numeric ordering beyond the six-digit padding.
      String next = String.format(Locale.ROOT, "CAP-%06d", highest.add(BigInteger.ONE));
      if (next.length() > 40) {
         throw new WTException("The next capture ID exceeds the 40-character persisted field.");
      }
      return next;
   }

   private static BigInteger parseSuffix(String captureId) {
      if (captureId == null || !captureId.matches("CAP-[0-9]+")) {
         return BigInteger.ZERO;
      }
      return new BigInteger(captureId.substring(4));
   }

   /**
    * Writes the collected changes and their column deltas.
    *
    * Each change is stored with its deltas so a partial failure still leaves a
    * usable record; a capture that half-succeeds is more useful to an
    * investigator than one that rolls back.
    */
   public static void storeChanges(DbCaptureSession session, List<CapturedChange> changes)
         throws WTException, WTPropertyVetoException {
      for (CapturedChange captured : changes) {
         requireStoredIdentifier(captured.getTableName(), "table");
         for (CapturedChange.Delta delta : captured.getDeltas()) {
            requireStoredIdentifier(delta.getColumn(), "column");
         }
      }
      for (CapturedChange captured : changes) {
         DbCaptureChange change = DbCaptureChange.newDbCaptureChange(session);
         change.setTableName(captured.getTableName());
         change.setClassName(captured.getClassName());
         change.setOperation(captured.getOperation());
         change.setTargetRowId(captured.getRowId());
         change.setObjectIdentity(clip(captured.getObjectIdentity(), 400));
         change.setChangedColumns(clip(captured.changedColumnList(), 4000));
         change.setChangeTime(captured.getChangeTime());
         change.setChangeScn(captured.getChangeScn());
         change.setTransactionId(captured.getTransactionId());

         CorrelationHit hit = captured.getCorrelation();
         if (hit != null) {
            change.setActionName(clip(hit.getActionName(), 400));
            change.setRequestUri(clip(hit.getRequestUri(), 400));
            change.setChangedBy(clip(hit.getUser(), 200));
            change.setStackTrace(hit.getStackTrace());
         }

         change = (DbCaptureChange) PersistenceHelper.manager.store(change);

         for (CapturedChange.Delta delta : captured.getDeltas()) {
            DbCaptureAttrDelta row = DbCaptureAttrDelta.newDbCaptureAttrDelta(change);
            row.setColumnName(delta.getColumn());
            String oldValue = delta.getOldValue();
            String newValue = delta.getNewValue();
            row.setTruncated(tooLong(oldValue) || tooLong(newValue));
            row.setOldValue(clip(oldValue, DbCaptureAttrDelta.VALUE_LIMIT));
            row.setNewValue(clip(newValue, DbCaptureAttrDelta.VALUE_LIMIT));

            // Context copied down so the results read as one flat table.
            row.setCaptureId(session.getCaptureId());
            row.setTableName(change.getTableName());
            row.setClassName(change.getClassName());
            row.setOperation(change.getOperation());
            row.setTargetRowId(change.getTargetRowId());
            row.setObjectIdentity(change.getObjectIdentity());
            row.setChangeTime(change.getChangeTime());
            row.setChangeScn(change.getChangeScn());
            row.setTransactionId(change.getTransactionId());
            row.setActionName(change.getActionName());
            row.setChangedBy(change.getChangedBy());
            row.setRequestUri(change.getRequestUri());

            PersistenceHelper.manager.store(row);
         }
      }

      storeTableRollup(session, changes);
   }

   private static void requireStoredIdentifier(String name, String kind) throws WTException {
      if (name == null || name.isEmpty() || name.length() > MonitoringScope.CAPTURE_NAME_LIMIT) {
         throw new WTException("Cannot store capture " + kind + " identifier " + name
               + ": identifiers must fit the " + MonitoringScope.CAPTURE_NAME_LIMIT
               + "-character persisted field and are never truncated.");
      }
   }

   /**
    * Rolls the stored changes up into one row per table and operation.
    *
    * Per table <i>and operation</i>, not per table: "WTPART, CREATE x2,
    * UPDATE x3" is a summary of two different events that happen to share a
    * table, and reading it means unpicking the tally again. Two rows, one
    * saying CREATE and one saying UPDATE, answer "what did my click do to this
    * table" directly - which is the question the tool exists for.
    *
    * Written at capture time rather than computed on every page view: the
    * rollup is fixed once the capture ends, and doing it here keeps the
    * results page to a single ordered read.
    */
   private static void storeTableRollup(DbCaptureSession session,
                                        List<CapturedChange> changes)
         throws WTException, WTPropertyVetoException {

      // Keyed on table and operation together. The separator is a character
      // that cannot occur in either, so no pair of real values can collide.
      Map<String, List<CapturedChange>> byTable =
            new LinkedHashMap<String, List<CapturedChange>>();
      for (CapturedChange change : changes) {
         String key = change.getTableName() + '\0' + change.getOperation();
         List<CapturedChange> list = byTable.get(key);
         if (list == null) {
            list = new ArrayList<CapturedChange>();
            byTable.put(key, list);
         }
         list.add(change);
      }

      for (Map.Entry<String, List<CapturedChange>> entry : byTable.entrySet()) {
         List<CapturedChange> group = entry.getValue();
         String key = entry.getKey();
         int split = key.indexOf('\0');
         String tableName = key.substring(0, split);
         String operation = key.substring(split + 1);

         DbCaptureTableChange row = DbCaptureTableChange.newDbCaptureTableChange(session);
         row.setCaptureId(session.getCaptureId());
         row.setTableName(tableName);
         row.setRowsAffected(group.size());

         int created = 0, updated = 0, deleted = 0, logical = 0;
         Set<String> identities = new LinkedHashSet<String>();
         Set<String> actions = new LinkedHashSet<String>();
         Set<String> users = new LinkedHashSet<String>();
         String className = null;
         Timestamp first = null;
         Timestamp last = null;
         StringBuilder details = new StringBuilder();

         for (CapturedChange change : group) {
            String op = change.getOperation();
            if (DbCaptureChange.OP_CREATE.equals(op)) {
               created++;
            } else if (DbCaptureChange.OP_DELETE.equals(op)) {
               deleted++;
            } else if (DbCaptureChange.OP_LOGICAL_DELETE.equals(op)) {
               logical++;
            } else {
               updated++;
            }
            if (className == null) {
               className = change.getClassName();
            }
            addIfSet(identities, change.getObjectIdentity());
            CorrelationHit hit = change.getCorrelation();
            if (hit != null) {
               addIfSet(actions, hit.getActionName());
               addIfSet(users, hit.getUser());
            }
            Timestamp when = change.getChangeTime();
            if (when != null) {
               if (first == null || when.before(first)) { first = when; }
               if (last == null || when.after(last)) { last = when; }
            }
            appendDetail(details, change);
         }

         row.setClassName(clip(className, 200));
         row.setCreatedCount(created);
         row.setUpdatedCount(updated);
         row.setDeletedCount(deleted);
         row.setLogicalDeletedCount(logical);
         // One operation per row now, so this is the operation itself rather
         // than a tally. The property keeps its generated name; renaming it
         // would mean a model change and a schema reinstall for nothing.
         row.setOperations(clip(operation, 200));
         row.setObjectIdentities(clip(join(identities), 4000));
         row.setActionNames(clip(join(actions), 4000));
         row.setChangedBy(clip(join(users), 400));
         row.setFirstChangeTime(first);
         row.setLastChangeTime(last);
         row.setDetails(details.toString());
         PersistenceHelper.manager.store(row);
      }
   }

   /**
    * One block per changed row: the row, then its columns indented under it.
    *
    * No operation prefix - the rollup row this text belongs to is already one
    * operation, so repeating it on every line is noise.
    */
   private static void appendDetail(StringBuilder details, CapturedChange change) {
      details.append("IDA2A2=").append(change.getRowId());
      if (change.getObjectIdentity() != null) {
         details.append("  (").append(change.getObjectIdentity()).append(')');
      }
      details.append('\n');
      for (CapturedChange.Delta delta : change.getDeltas()) {
         details.append("    ").append(delta.getColumn()).append(": ")
                .append(render(delta.getOldValue()))
                .append("  ->  ")
                .append(render(delta.getNewValue()))
                .append('\n');
      }
   }

   private static String render(String value) {
      if (value == null) {
         return "(null)";
      }
      String flat = value.replace('\n', ' ').replace('\r', ' ');
      return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
   }

   private static void addIfSet(Set<String> target, String value) {
      if (value != null && !value.trim().isEmpty()) {
         target.add(value.trim());
      }
   }

   private static String join(Set<String> values) {
      StringBuilder sb = new StringBuilder();
      for (String v : values) {
         if (sb.length() > 0) {
            sb.append(", ");
         }
         sb.append(v);
      }
      return sb.length() == 0 ? null : sb.toString();
   }

   /**
    * Per table and operation rollup rows, newest capture first.
    *
    * These are what the results page shows. Filtering is left to the caller
    * for the same reason as findAllDeltas - the keyword spans the details
    * text, and there are only a handful of rows per capture.
    */
   public static QueryResult findTableChanges() throws WTException {
      QuerySpec qs = new QuerySpec(DbCaptureTableChange.class);
      qs.appendOrderBy(new OrderBy(
            new wt.query.ClassAttribute(DbCaptureTableChange.class,
                  DbCaptureTableChange.LAST_CHANGE_TIME), true));
      return PersistenceHelper.manager.find(qs);
   }

   /** The rollup rows of one session. */
   public static QueryResult findTableChanges(DbCaptureSession session)
         throws WTException {
      QuerySpec qs = new QuerySpec(DbCaptureTableChange.class);
      qs.appendWhere(new SearchCondition(DbCaptureTableChange.class,
            DbCaptureTableChange.SESSION_REFERENCE + ".key.id",
            SearchCondition.EQUAL,
            PersistenceHelper.getObjectIdentifier(session).getId()), new int[] { 0 });
      return PersistenceHelper.manager.find(qs);
   }

   /** Stores a session, inserting or updating as appropriate. */
   public static DbCaptureSession save(DbCaptureSession session) throws WTException {
      if (PersistenceHelper.isPersistent(session)) {
         return (DbCaptureSession) PersistenceHelper.manager.modify(session);
      }
      return (DbCaptureSession) PersistenceHelper.manager.store(session);
   }

   /** Stores an edited change row, used by the editable Note column. */
   public static DbCaptureChange save(DbCaptureChange change) throws WTException {
      return (DbCaptureChange) PersistenceHelper.manager.modify(change);
   }

   /** Deletes a session together with everything it recorded. */
   public static void deleteSession(DbCaptureSession session) throws WTException {
      DbCaptureAuthorization.requireAdministrator();
      if (session == null || !PersistenceHelper.isPersistent(session)) {
         throw new WTException("No persisted capture session was selected.");
      }
      // Do not trust a stale or caller-modified status when deciding to delete.
      session = (DbCaptureSession) PersistenceHelper.manager.refresh(
            PersistenceHelper.getObjectIdentifier(session));
      if (session == null) {
         throw new WTException("The selected capture session no longer exists.");
      }
      if (DbCaptureSession.STATUS_RUNNING.equals(session.getStatus())) {
         throw new WTException("Cannot delete running DB capture "
               + session.getCaptureId()
               + ". Use Stop DB Capture or Abort DB Capture before deleting it.");
      }
      QueryResult changes = findChanges(session);
      while (changes.hasMoreElements()) {
         DbCaptureChange change = (DbCaptureChange) changes.nextElement();
         QueryResult deltas = findDeltas(change);
         while (deltas.hasMoreElements()) {
            PersistenceHelper.manager.delete((Persistable) deltas.nextElement());
         }
         PersistenceHelper.manager.delete(change);
      }
      // The rollup rows too. They were missed while nothing read them, which
      // left every delete behind a set of rows pointing at a session that no
      // longer exists - invisible then, ghost rows in Database Changes now
      // that the results page reads this table.
      QueryResult rollups = findTableChanges(session);
      while (rollups.hasMoreElements()) {
         PersistenceHelper.manager.delete((Persistable) rollups.nextElement());
      }
      DbCaptureDiagnostics.delete(session);
      PersistenceHelper.manager.delete(session);
   }

   private static boolean tooLong(String s) {
      return s != null && s.length() > DbCaptureAttrDelta.VALUE_LIMIT;
   }

   private static String clip(String s, int limit) {
      if (s == null) {
         return null;
      }
      return s.length() > limit ? s.substring(0, limit - 3) + "..." : s;
   }
}
