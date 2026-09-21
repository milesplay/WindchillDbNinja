package com.ptc.dbcapture.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evidence from native SQL logging, not a transaction audit or an object identity resolver.
 * A logged statement precedes JDBC execution and does not prove success, a commit, or a PBO id.
 */
public final class SqlEvidence {
   public static final String SOURCE = "Windchill wt.pom.sql (pre-execution), synchronous caller stack";
   public static final String ATTRIBUTION =
         "Authenticated servlet request and physical table only; no exact PBO/row or commit attribution.";
   public static final String COVERAGE =
         "This MethodServer only. INSERT/UPDATE/DELETE/MERGE on the explicit table allowlist. "
       + "No SELECTs, background work, or DB Capture requests. Only native completed requests "
       + "without a recorded servlet/captured-method-context error are retained. "
       + "Direct JDBC and other paths without native SQL logging are not covered. "
       + "Native bind text, if already present, is not expanded or interpreted as object identity; "
       + "DEBUG/bind logging is not enabled by this capture.";

   private SqlEvidence() {
   }

   public enum State {
      ACTIVE, COMPLETE, ABORTED, TIMED_OUT, LIMIT_REACHED, UNAVAILABLE, ERROR, INTERRUPTED
   }

   public static final class Limits {
      public static final long MAX_DURATION_MILLIS = 600_000L;
      public static final long MAX_BYTES = 64L * 1024L * 1024L;
      public static final int MAX_EVENTS = 10_000;
      public final long durationMillis;
      public final int maxEvents;
      public final long maxBytes;
      /** Counts distinct pending request/method-context pairs, not just HTTP requests. */
      public final int maxPendingRequests;
      public final int maxMessageChars;
      public final int maxStackFrames;

      public Limits(long durationMillis, int maxEvents, long maxBytes, int maxPendingRequests,
                    int maxMessageChars, int maxStackFrames) {
         if (durationMillis < 250 || durationMillis > MAX_DURATION_MILLIS
               || maxEvents < 1 || maxEvents > MAX_EVENTS
               || maxBytes < 4096 || maxBytes > MAX_BYTES
               || maxPendingRequests < 1 || maxPendingRequests > 512
               || maxMessageChars < 256 || maxMessageChars > 65_536
               || maxStackFrames < 8 || maxStackFrames > 256) {
            throw new IllegalArgumentException("Evidence limits are outside the supported bounds");
         }
         this.durationMillis = durationMillis;
         this.maxEvents = maxEvents;
         this.maxBytes = maxBytes;
         this.maxPendingRequests = maxPendingRequests;
         this.maxMessageChars = maxMessageChars;
         this.maxStackFrames = maxStackFrames;
      }

      public static Limits defaults() {
         return forDuration(120_000L);
      }

      public static Limits forDuration(long durationMillis) {
         return new Limits(durationMillis, 2000, 8L * 1024L * 1024L, 128, 32_768, 192);
      }
   }

   public static final class Statement {
      private final String operation;
      private final String table;
      private final String nativeMessage;

      Statement(String operation, String table, String nativeMessage) {
         this.operation = operation;
         this.table = table;
         this.nativeMessage = nativeMessage;
      }

      public String getOperation() { return operation; }
      public String getTable() { return table; }

      /** The actual native message, including placeholders and any native bind annotation. */
      public String getNativeMessage() { return nativeMessage; }
   }

   public static final class Event {
      private final long sequence;
      private final long timestampMillis;
      private final long threadId;
      private final String threadName;
      private final String servletRequestId;
      private final String methodContextId;
      private final String authenticatedUser;
      private final String methodUser;
      private final String requestUri;
      private final String targetClass;
      private final String targetMethod;
      private final Statement statement;
      private final List<String> stack;
      private final boolean stackTruncated;

      Event(long sequence, long timestampMillis, long threadId, String threadName,
            String servletRequestId, String methodContextId, String authenticatedUser,
            String methodUser, String requestUri, String targetClass, String targetMethod,
            Statement statement, List<String> stack, boolean stackTruncated) {
         this.sequence = sequence;
         this.timestampMillis = timestampMillis;
         this.threadId = threadId;
         this.threadName = threadName;
         this.servletRequestId = servletRequestId;
         this.methodContextId = methodContextId;
         this.authenticatedUser = authenticatedUser;
         this.methodUser = methodUser;
         this.requestUri = requestUri;
         this.targetClass = targetClass;
         this.targetMethod = targetMethod;
         this.statement = statement;
         this.stack = Collections.unmodifiableList(new ArrayList<String>(stack));
         this.stackTruncated = stackTruncated;
      }

      public long getSequence() { return sequence; }
      public long getTimestampMillis() { return timestampMillis; }
      public long getThreadId() { return threadId; }
      public String getThreadName() { return threadName; }
      public String getServletRequestId() { return servletRequestId; }
      public String getMethodContextId() { return methodContextId; }
      public String getAuthenticatedUser() { return authenticatedUser; }
      public String getMethodUser() { return methodUser; }
      public String getRequestUri() { return requestUri; }
      public String getTargetClass() { return targetClass; }
      public String getTargetMethod() { return targetMethod; }
      public Statement getStatement() { return statement; }
      public List<String> getStack() { return stack; }
      public boolean isStackTruncated() { return stackTruncated; }
   }

   public static final class Snapshot {
      private final String sessionOid;
      private final State state;
      private final String reason;
      private final String node;
      private final long startedMillis;
      private final long deadlineMillis;
      private final long finishedMillis;
      private final long observedMessages;
      private final long filteredStatements;
      private final long rejectedContexts;
      private final long failedRequestStatements;
      private final long unfinishedRequestStatements;
      private final long limitDiscardedStatements;
      private final List<Event> events;
      private final List<String> tables;
      private final List<String> notRecordedTables;

      Snapshot(String sessionOid, State state, String reason, String node, long startedMillis,
               long deadlineMillis, long finishedMillis, long observedMessages,
               long filteredStatements, long rejectedContexts, long failedRequestStatements,
               long unfinishedRequestStatements, long limitDiscardedStatements,
               List<Event> events, List<String> tables) {
         this(sessionOid, state, reason, node, startedMillis, deadlineMillis, finishedMillis,
               observedMessages, filteredStatements, rejectedContexts, failedRequestStatements,
               unfinishedRequestStatements, limitDiscardedStatements, events, tables, null);
      }

      Snapshot(String sessionOid, State state, String reason, String node, long startedMillis,
               long deadlineMillis, long finishedMillis, long observedMessages,
               long filteredStatements, long rejectedContexts, long failedRequestStatements,
               long unfinishedRequestStatements, long limitDiscardedStatements,
               List<Event> events, List<String> tables, List<String> notRecordedTables) {
         this.sessionOid = sessionOid;
         this.state = state;
         this.reason = reason;
         this.node = node;
         this.startedMillis = startedMillis;
         this.deadlineMillis = deadlineMillis;
         this.finishedMillis = finishedMillis;
         this.observedMessages = observedMessages;
         this.filteredStatements = filteredStatements;
         this.rejectedContexts = rejectedContexts;
         this.failedRequestStatements = failedRequestStatements;
         this.unfinishedRequestStatements = unfinishedRequestStatements;
         this.limitDiscardedStatements = limitDiscardedStatements;
         this.events = Collections.unmodifiableList(new ArrayList<Event>(events));
         this.tables = List.copyOf(tables);
         this.notRecordedTables = notRecordedTables == null ? null : List.copyOf(notRecordedTables);
      }

      public String getSessionOid() { return sessionOid; }
      public State getState() { return state; }
      public String getReason() { return reason; }
      public String getNode() { return node; }
      public long getStartedMillis() { return startedMillis; }
      public long getDeadlineMillis() { return deadlineMillis; }
      public long getFinishedMillis() { return finishedMillis; }
      public long getObservedMessages() { return observedMessages; }
      public long getFilteredStatements() { return filteredStatements; }
      public long getRejectedContexts() { return rejectedContexts; }
      public long getFailedRequestStatements() { return failedRequestStatements; }
      public long getUnfinishedRequestStatements() { return unfinishedRequestStatements; }
      public long getLimitDiscardedStatements() { return limitDiscardedStatements; }
      public List<Event> getEvents() { return events; }
      public List<String> getTables() { return tables; }
      public boolean isNotRecordedScopeAvailable() { return notRecordedTables != null; }
      public List<String> getNotRecordedTables() {
         return notRecordedTables == null ? List.of() : notRecordedTables;
      }

      static Snapshot unavailable(String oid, String reason) {
         return new Snapshot(oid, State.UNAVAILABLE, reason, "", 0, 0, 0,
               0, 0, 0, 0, 0, 0, Collections.<Event>emptyList(), List.of());
      }
   }
}
