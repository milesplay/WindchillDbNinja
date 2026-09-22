package com.custom.dbcapture.diagnostics;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.AbstractMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.filter.MarkerFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;

/**
 * A bounded in-process observer of the installed native wt.pom.sql logging calls.
 *
 * <p>Open only after the session has a persistent OID. Hold one window per MethodServer;
 * call stop on normal Stop and abort on Abort, session deletion, or service failure.
 * Always close it in error/finally paths. A watchdog and JVM shutdown hook also close it.
 * Authorize session access in the service/UI; this class is not an authorization boundary.</p>
 *
 * <p>No logger level, LoggerConfig, appender, additivity, profiler adapter, or configuration
 * file is changed. A temporary Log4j context filter enables INFO only on authenticated
 * foreground AJP requests. It observes native messages synchronously, retaining the actual
 * calling stack, and suppresses messages that the pre-existing level would not have emitted.
 * Existing global filters cause an explicit unavailable result rather than overriding
 * their ACCEPT/DENY ordering. Reconfiguration cancels the window, preserving the new config.</p>
 *
 * <p>DEBUG is deliberately not enabled: generating arbitrary native bind representations
 * cannot be bounded by this observer and would also enrich pre-existing INFO log destinations.
 * Any bind text already included by native logging is retained verbatim, never reconstructed.</p>
 *
 * <p>The installed native StandardProfilerService is intentionally not started: enableAdapters
 * clears shared profile maps, and its username/method-context filters and lifecycle are global.
 * ProfilingListener only enables/disables an adapter; it is not a per-session event listener.
 * Its default maxProfHits=20 is a polling threshold, not a storage cap; its default timeout is
 * 30 minutes. The public Log4j filtering API avoids taking over a customer's profiler run.</p>
 *
 * <p>Native statements are logged before JDBC execution. This is request/table evidence,
 * never proof of successful execution, commit, a particular bind-to-column mapping, or
 * the identity of a changed PBO. Pending request data stays in bounded memory and is only
 * persisted after the native servlet monitor reports completion without an error.</p>
 */
public final class SqlEvidenceCapture implements AutoCloseable {
   private static final String SQL_LOGGER = "wt.pom.sql";
   private static final Pattern AJP = Pattern.compile("^ajp[-:].*-exec-[0-9]+$", Pattern.CASE_INSENSITIVE);
   private static final Map<LoggerContext, SqlEvidenceCapture> WINDOWS =
         new IdentityHashMap<LoggerContext, SqlEvidenceCapture>();
   private static final ThreadLocal<Integer> SUPPRESSED = new ThreadLocal<Integer>();
   private final ThreadLocal<Boolean> insideFilter = new ThreadLocal<Boolean>();
   private final Object lifecycle = new Object();
   private final Object filterLock = new Object();
   private final Object dataLock = new Object();
   private final LoggerContext context;
   private final Configuration configuration;
   private final Filter baselineFilter;
   private final RequestProbe probe;
   private final SessionEvidenceStore store;
   private final String sessionOid;
   private final SessionEvidenceStore.Writer writer;
   private final SqlStatementClassifier classifier;
   private final SqlEvidence.Limits limits;
   private final ScopedFilter filter = new ScopedFilter();
   private final Map<String, PendingRequest> pending = new LinkedHashMap<String, PendingRequest>();
   private final long[] counters = new long[6];
   private final long deadlineNanos;
   private final PropertyChangeListener configurationListener;
   private volatile SqlEvidence.State state = SqlEvidence.State.ACTIVE;
   private volatile String reason = "Window open; completed request evidence only.";
   private volatile IOException persistenceFailure;
   private volatile boolean accepting;
   private volatile boolean closed;
   private boolean filterInstalled;
   private boolean listenerInstalled;
   private boolean registryOwned;
   private boolean hookInstalled;
   private Thread shutdownHook;
   private ScheduledThreadPoolExecutor watchdog;
   private ScheduledFuture<?> watchdogTask;
   private ScheduledFuture<?> deadlineTask;
   private long nextSequence;
   private long reservedBytes;
   private int pendingContexts;

   public enum Outcome { PENDING, SUCCEEDED, REJECTED, UNAVAILABLE }

   @FunctionalInterface
   public interface Completion {
      Outcome outcome() throws Exception;
   }

   @FunctionalInterface
   public interface RequestProbe {
      /** Returns current native request identity, never a sampled or thread-name-only attribution. */
      RequestInfo current() throws Exception;
   }

   public static final class RequestInfo {
      public final long threadId;
      public final String threadName;
      public final String requestId;
      public final String methodContextId;
      public final String authenticatedUser;
      public final String methodUser;
      public final String requestUri;
      public final String queryString;
      public final String targetClass;
      public final String targetMethod;
      public final boolean authenticated;
      public final Completion completion;

      public RequestInfo(long threadId, String threadName, String requestId, String methodContextId,
                         String authenticatedUser, String methodUser, String requestUri, String queryString,
                         String targetClass, String targetMethod, boolean authenticated, Completion completion) {
         this.threadId = threadId;
         this.threadName = threadName;
         this.requestId = requestId;
         this.methodContextId = methodContextId;
         this.authenticatedUser = authenticatedUser;
         this.methodUser = methodUser;
         this.requestUri = requestUri;
         this.queryString = queryString;
         this.targetClass = targetClass;
         this.targetMethod = targetMethod;
         this.authenticated = authenticated;
         this.completion = completion;
      }
   }

   public static SqlEvidenceCapture open(Path privateRoot, String sessionOid, Collection<String> tables,
                                         SqlEvidence.Limits limits) throws IOException {
      return open(privateRoot, sessionOid, tables, null, limits);
   }

   public static SqlEvidenceCapture open(Path privateRoot, String sessionOid, Collection<String> tables,
                                         Collection<String> tableCatalog, SqlEvidence.Limits limits) throws IOException {
      org.apache.logging.log4j.spi.LoggerContext selected = LogManager.getContext(false);
      SessionEvidenceStore store = new SessionEvidenceStore(privateRoot);
      if (tables != null && tables.isEmpty()) {
         return unavailable(store, sessionOid, tables, tableCatalog, limits,
               "No eligible physical tables are included in this capture's monitoring scope.");
      }
      if (!(selected instanceof LoggerContext)) {
         return unavailable(store, sessionOid, tables, tableCatalog, limits,
               "Log4j Core is unavailable on this MethodServer.");
      }
      try {
         return open((LoggerContext) selected, new WindchillRequestProbe(), store, sessionOid,
               tables, tableCatalog, limits);
      } catch (ReflectiveOperationException | LinkageError e) {
         return unavailable(store, sessionOid, tables, tableCatalog, limits,
               "Required native Windchill servlet/MethodContext APIs are unavailable. No tracing was enabled.");
      }
   }

   /** Dependency-injected entry point for isolated tests; production uses the overload above. */
   public static SqlEvidenceCapture open(LoggerContext context, RequestProbe probe, SessionEvidenceStore store,
                                         String sessionOid, Collection<String> tables,
                                         SqlEvidence.Limits limits) throws IOException {
      return open(context, probe, store, sessionOid, tables, null, limits);
   }

   public static SqlEvidenceCapture open(LoggerContext context, RequestProbe probe, SessionEvidenceStore store,
                                         String sessionOid, Collection<String> tables,
                                         Collection<String> tableCatalog, SqlEvidence.Limits limits) throws IOException {
      if (context == null || probe == null || store == null || limits == null) {
         throw new IllegalArgumentException("Capture dependencies and limits are required");
      }
      if (tables != null && tables.isEmpty()) {
         return unavailable(store, sessionOid, tables, tableCatalog, limits,
               "No eligible physical tables are included in this capture's monitoring scope.");
      }
      SqlStatementClassifier classifier = new SqlStatementClassifier(tables);
      SessionEvidenceStore.Writer writer = store.begin(sessionOid, limits, classifier.getTables(), tableCatalog);
      SqlEvidenceCapture window = new SqlEvidenceCapture(context, probe, store, sessionOid, classifier, limits, writer);
      try {
         window.install();
      } catch (Throwable e) {
         window.finish(SqlEvidence.State.UNAVAILABLE,
               "Tracing could not be installed (" + e.getClass().getSimpleName() + "); no logger levels were changed.");
         rethrowFatal(e);
      }
      return window;
   }

   private static SqlEvidenceCapture unavailable(SessionEvidenceStore store, String sessionOid,
         Collection<String> tables, Collection<String> tableCatalog, SqlEvidence.Limits limits,
         String reason) throws IOException {
      SqlStatementClassifier classifier = tables != null && tables.isEmpty() ? null : new SqlStatementClassifier(tables);
      SessionEvidenceStore.Writer writer = store.begin(sessionOid, limits,
            classifier == null ? Set.of() : classifier.getTables(), tableCatalog);
      SqlEvidenceCapture window = new SqlEvidenceCapture(null, null, store, sessionOid, classifier, limits, writer);
      window.finish(SqlEvidence.State.UNAVAILABLE, reason);
      return window;
   }

   private SqlEvidenceCapture(LoggerContext context, RequestProbe probe, SessionEvidenceStore store,
         String sessionOid, SqlStatementClassifier classifier, SqlEvidence.Limits limits,
         SessionEvidenceStore.Writer writer) throws IOException {
      this.context = context;
      this.configuration = context == null ? null : context.getConfiguration();
      this.baselineFilter = configuration == null ? null : configuration.getFilter();
      this.probe = probe;
      this.store = store;
      this.sessionOid = SessionEvidenceStore.canonicalSessionOid(sessionOid);
      this.classifier = classifier;
      this.limits = limits;
      this.writer = writer;
      this.reservedBytes = writer.getBytes();
      this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.durationMillis);
      this.configurationListener = event -> {
         if (this.context.getConfiguration() != this.configuration) {
            if (this.context.isStopping() || this.context.isStopped()) {
               finishQuietly(SqlEvidence.State.INTERRUPTED, "The MethodServer logging context stopped; tracing was removed.");
            } else {
               finishQuietly(SqlEvidence.State.UNAVAILABLE,
                     "Log4j was reconfigured during capture; the window was cancelled without altering the new configuration.");
            }
         }
      };
   }

   private void install() throws IOException {
      synchronized (lifecycle) {
         installLocked();
      }
   }

   private void installLocked() throws IOException {
      synchronized (WINDOWS) {
         if (WINDOWS.containsKey(context)) {
            finish(SqlEvidence.State.UNAVAILABLE, "Another SQL evidence window is active on this MethodServer.");
            return;
         }
         WINDOWS.put(context, this);
         registryOwned = true;
      }
      synchronized (configuration) {
         if (context.getConfiguration() != configuration || configuration.getFilter() != baselineFilter
            || baselineFilter != null && !isQualifiedBaselineFilter(baselineFilter)) {
            finish(SqlEvidence.State.UNAVAILABLE,
                  "An existing global Log4j filter or concurrent reconfiguration prevents safely scoped tracing. "
                + "Its configuration was left unchanged.");
            return;
         }
         filter.start();
         configuration.addFilter(filter);
         filterInstalled = true;
      }
      context.addPropertyChangeListener(configurationListener);
      listenerInstalled = true;
      shutdownHook = new Thread(() -> finishQuietly(SqlEvidence.State.INTERRUPTED,
            "MethodServer shutdown ended the capture window."), "DbCapture-evidence-shutdown");
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      hookInstalled = true;
      // A deadline worker remains available even if the local evidence writer stalls.
      watchdog = new ScheduledThreadPoolExecutor(2, task -> {
         Thread thread = new Thread(task, "DbCapture-evidence-watchdog");
         thread.setDaemon(true);
         return thread;
      });
      watchdog.setRemoveOnCancelPolicy(true);
      if (context.getConfiguration() != configuration || !ownsInstalledFilter()
            || context.isStopped() || configuration.isStopped()) {
         finish(SqlEvidence.State.UNAVAILABLE, "Log4j changed while the capture window was being installed.");
         return;
      }
      accepting = true;
      watchdogTask = watchdog.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
      deadlineTask = watchdog.schedule(() -> endFromObserver(SqlEvidence.State.TIMED_OUT,
            "The maximum capture duration elapsed; tracing was removed automatically."),
            limits.durationMillis, TimeUnit.MILLISECONDS);
   }

   /** Explicitly excludes DB Capture's own collection and popup work on the current thread. */
   public static Suppression suppress() {
      Integer depth = SUPPRESSED.get();
      SUPPRESSED.set(depth == null ? 1 : depth + 1);
      return new Suppression(Thread.currentThread());
   }

   public static final class Suppression implements AutoCloseable {
      private final Thread owner;
      private boolean closed;

      private Suppression(Thread owner) { this.owner = owner; }

      @Override
      public void close() {
         if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Suppression must be closed on its owning thread");
         }
         if (!closed) {
            closed = true;
            int depth = SUPPRESSED.get();
            if (depth == 1) {
               SUPPRESSED.remove();
            } else {
               SUPPRESSED.set(depth - 1);
            }
         }
      }
   }

   public SqlEvidence.State getState() { return state; }
   public String getReason() { return reason; }
   public boolean isActive() { return accepting; }
   public boolean isClosed() { return closed; }
   public String getSessionOid() { return sessionOid; }
   public SqlEvidence.Snapshot snapshot() throws IOException { return store.read(sessionOid); }

   public void stop() throws IOException {
      finish(SqlEvidence.State.COMPLETE, "Stopped. Missing SQL/binds are unavailable evidence, not proof of no changes.");
   }

   public void abort() throws IOException {
      finish(SqlEvidence.State.ABORTED, "Aborted. Completed request evidence retained; unfinished request evidence discarded.");
   }

   @Override
   public void close() throws IOException {
      abort();
   }

   private void tick() {
      try {
         if (closed) {
            return;
         }
         if (System.nanoTime() - deadlineNanos >= 0) {
            finish(SqlEvidence.State.TIMED_OUT, "The maximum capture duration elapsed; tracing was removed automatically.");
         } else if (context.isStopping() || context.isStopped()) {
            finish(SqlEvidence.State.INTERRUPTED, "The MethodServer logging context stopped; tracing was removed.");
            } else if (context.getConfiguration() != configuration || !ownsInstalledFilter()
               || configuration.isStopped()) {
            finish(SqlEvidence.State.UNAVAILABLE,
                  "Log4j configuration/filter ownership changed; tracing was removed without resetting any logger.");
         } else {
            synchronized (lifecycle) {
               if (!closed) {
                  flushCompleted(false);
                  writer.checkpoint(SqlEvidence.State.ACTIVE, "Window open; completed request evidence only.",
                        counterSnapshot(), 0);
               }
            }
         }
      } catch (Throwable e) {
         finishQuietly(SqlEvidence.State.ERROR, "Evidence collection failed (" + e.getClass().getSimpleName() + ").");
         rethrowFatal(e);
      }
   }

   private void finishQuietly(SqlEvidence.State terminal, String detail) {
      try {
         finish(terminal, detail);
      } catch (IOException ignored) {
         // Exposed by stop()/abort(), getReason(), and the interrupted on-disk checkpoint.
      }
   }

   private void finish(SqlEvidence.State terminal, String detail) throws IOException {
      disableObserver(terminal, detail);
      synchronized (lifecycle) {
         if (closed) {
            if (persistenceFailure != null) {
               throw persistenceFailure;
            }
            return;
         }
         detach();
         try {
            flushCompleted(true);
            writer.checkpoint(state, reason, counterSnapshot(), System.currentTimeMillis());
         } catch (IOException | RuntimeException e) {
            state = SqlEvidence.State.ERROR;
            reason = "Evidence persistence failed; the last durable checkpoint may be incomplete.";
            persistenceFailure = e instanceof IOException ? (IOException) e : new IOException(reason, e);
         } finally {
            synchronized (dataLock) {
               pending.clear();
            }
            try {
               writer.close();
            } catch (IOException e) {
               state = SqlEvidence.State.ERROR;
               reason = "Evidence could not be closed cleanly; inspect the durable checkpoint.";
               if (persistenceFailure == null) {
                  persistenceFailure = e;
               } else {
                  persistenceFailure.addSuppressed(e);
               }
            } finally {
               releaseRegistry();
               closed = true;
            }
         }
         if (persistenceFailure != null) {
            throw persistenceFailure;
         }
      }
   }

   private void detach() {
      filter.stop();
      if (listenerInstalled) {
         context.removePropertyChangeListener(configurationListener);
         listenerInstalled = false;
      }
      if (watchdogTask != null) {
         watchdogTask.cancel(false);
      }
      if (deadlineTask != null) {
         deadlineTask.cancel(false);
      }
      if (watchdog != null) {
         watchdog.shutdown();
      }
      if (hookInstalled && Thread.currentThread() != shutdownHook) {
         try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
         } catch (IllegalStateException ignored) {
            // A JVM shutdown is already executing the hook.
         }
         hookInstalled = false;
      }
   }

   private void releaseRegistry() {
      if (registryOwned) {
         synchronized (WINDOWS) {
            if (WINDOWS.get(context) == this) {
               WINDOWS.remove(context);
            }
            registryOwned = false;
         }
      }
   }

   private boolean disableObserver(SqlEvidence.State terminal, String detail) {
      synchronized (filterLock) {
         boolean first = state == SqlEvidence.State.ACTIVE;
         accepting = false;
         if (first) {
            state = terminal;
            reason = detail;
         }
         if (filterInstalled) {
            configuration.removeFilter(filter);
            filterInstalled = false;
         }
         return first;
      }
   }

   private static boolean isQualifiedBaselineFilter(Filter existing) {
      return existing instanceof MarkerFilter && existing.toString().contains("ReflectionFilter");
   }

   private boolean ownsInstalledFilter() {
      Filter current = configuration.getFilter();
      if (baselineFilter == null) return current == filter;
      if (!(current instanceof CompositeFilter)) return false;
      boolean baselinePresent = false;
      boolean ownedPresent = false;
      int count = 0;
      for (Filter member : (CompositeFilter) current) {
         count++;
         baselinePresent |= member == baselineFilter;
         ownedPresent |= member == filter;
      }
      return count == 2 && baselinePresent && ownedPresent;
   }

   private void endFromObserver(SqlEvidence.State terminal, String detail) {
      if (disableObserver(terminal, detail) && watchdog != null && !watchdog.isShutdown()) {
         try {
            watchdog.execute(() -> finishQuietly(terminal, detail));
         } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // A concurrent Stop/shutdown owns final persistence; the logging hook is already gone.
         }
      }
   }

   private long[] counterSnapshot() {
      synchronized (dataLock) {
         return counters.clone();
      }
   }

   private void flushCompleted(boolean finishing) throws IOException {
      List<Map.Entry<String, PendingRequest>> requests = new ArrayList<Map.Entry<String, PendingRequest>>();
      synchronized (dataLock) {
         for (Map.Entry<String, PendingRequest> entry : pending.entrySet()) {
            requests.add(new AbstractMap.SimpleImmutableEntry<String, PendingRequest>(entry));
         }
      }
      for (Map.Entry<String, PendingRequest> entry : requests) {
         PendingRequest request = entry.getValue();
         List<Completion> completions;
         synchronized (dataLock) {
            completions = new ArrayList<Completion>(request.completions.values());
         }
         Outcome outcome = Outcome.SUCCEEDED;
         for (Completion completion : completions) {
            Outcome part;
            try {
               part = completion.outcome();
            } catch (Throwable e) {
               part = Outcome.UNAVAILABLE;
               rethrowFatal(e);
            }
            if (part == Outcome.REJECTED) {
               outcome = Outcome.REJECTED;
               break;
            } else if (part == Outcome.UNAVAILABLE || part == null) {
               outcome = Outcome.UNAVAILABLE;
            } else if (part == Outcome.PENDING && outcome != Outcome.UNAVAILABLE) {
               outcome = Outcome.PENDING;
            }
         }
         List<byte[]> toWrite = null;
         synchronized (dataLock) {
            if (request.completions.size() != completions.size()) {
               continue;
            }
            if (outcome == Outcome.SUCCEEDED) {
               toWrite = new ArrayList<byte[]>(request.payloads);
               pending.remove(entry.getKey());
               pendingContexts -= request.completions.size();
            } else if (outcome == Outcome.REJECTED) {
               counters[3] += request.payloads.size();
               pending.remove(entry.getKey());
               pendingContexts -= request.completions.size();
            } else if (outcome == Outcome.UNAVAILABLE || outcome == null || finishing) {
               counters[4] += request.payloads.size();
               pending.remove(entry.getKey());
               pendingContexts -= request.completions.size();
            }
         }
         if (toWrite != null) {
            for (byte[] payload : toWrite) {
               writer.append(payload);
            }
         }
      }
   }

   private boolean eligible(RequestInfo request) {
      Thread thread = Thread.currentThread();
      return request != null && request.authenticated && request.completion != null
            && request.threadId == thread.getId() && thread.getName().equals(request.threadName)
            && AJP.matcher(request.threadName).matches()
            && present(request.requestId) && present(request.methodContextId)
            && present(request.authenticatedUser) && !anonymous(request.authenticatedUser)
            && bounded(request.methodUser) && present(request.requestUri)
            && bounded(request.targetClass) && bounded(request.targetMethod)
            && !excluded(request.requestUri) && !excluded(request.queryString)
            && !excluded(request.targetClass);
   }

   private static boolean present(String value) {
      return value != null && !value.isBlank() && value.length() <= 4096;
   }

   private static boolean bounded(String value) {
      return value == null || value.length() <= 4096;
   }

   private static boolean anonymous(String value) {
      return "anonymous".equalsIgnoreCase(value) || "anonymousUser".equalsIgnoreCase(value)
            || "guest".equalsIgnoreCase(value);
   }

   private static boolean excluded(String value) {
      if (value == null) {
         return false;
      }
      if (value.length() > 8192) {
         return true;
      }
      for (int i = 0; i < 3; i++) {
         String lowered = value.toLowerCase(Locale.ROOT);
         if (lowered.contains("dbcapture") || lowered.contains("j_security_check")
               || lowered.contains("login") || lowered.contains("logout")
               || lowered.contains("/oauth") || lowered.contains("/saml") || lowered.contains("/sso")) {
            return true;
         }
         try {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (decoded.equals(value)) {
               return false;
            }
            value = decoded;
         } catch (IllegalArgumentException e) {
            return true;
         }
      }
      return value.indexOf('%') >= 0;
   }

   private Filter.Result observe(Logger logger, Level level, Object message) {
      if (!SQL_LOGGER.equals(logger.getName()) || (level != Level.INFO && level != Level.DEBUG)
            || !accepting || SUPPRESSED.get() != null || Boolean.TRUE.equals(insideFilter.get())) {
         return Filter.Result.NEUTRAL;
      }
      Filter.Result original = logger.getLevel().isLessSpecificThan(level)
            ? Filter.Result.NEUTRAL : Filter.Result.DENY;
      if (level == Level.DEBUG && original == Filter.Result.DENY) {
         return Filter.Result.NEUTRAL;
      }
      insideFilter.set(Boolean.TRUE);
      try {
         if (System.nanoTime() - deadlineNanos >= 0) {
            endFromObserver(SqlEvidence.State.TIMED_OUT, "The maximum capture duration elapsed; tracing was removed automatically.");
            return original;
         }
         if (context.getConfiguration() != configuration || !ownsInstalledFilter()) {
            endFromObserver(SqlEvidence.State.UNAVAILABLE, "Log4j ownership changed; evidence capture was cancelled.");
            return original;
         }
         RequestInfo request = probe.current();
         if (!eligible(request)) {
            synchronized (dataLock) { counters[2]++; }
            return Filter.Result.NEUTRAL;
         }
         if (message == null) {
            return level == Level.INFO ? Filter.Result.ACCEPT : Filter.Result.NEUTRAL;
         }
         String text = message instanceof Message ? ((Message) message).getFormattedMessage() : message.toString();
         synchronized (dataLock) { counters[0]++; }
         SqlEvidence.Statement statement = classifier.classify(text);
         if (statement == null) {
            synchronized (dataLock) { counters[1]++; }
            return original;
         }
         if (text.length() > limits.maxMessageChars) {
            synchronized (dataLock) { counters[5]++; }
            endFromObserver(SqlEvidence.State.LIMIT_REACHED, "A native SQL message exceeded the size cap; no truncated SQL was stored.");
            return original;
         }
         List<StackWalker.StackFrame> frames = StackWalker.getInstance().walk(
               stream -> stream.limit(limits.maxStackFrames + 1L).collect(Collectors.toList()));
         for (StackWalker.StackFrame frame : frames) {
            if (frame.getClassName().startsWith("com.custom.dbcapture.")
                  && !frame.getClassName().startsWith("com.custom.dbcapture.diagnostics.")) {
               synchronized (dataLock) { counters[2]++; }
               return original;
            }
         }
         boolean truncated = frames.size() > limits.maxStackFrames;
         List<String> stack = new ArrayList<String>();
         for (int i = 0; i < Math.min(frames.size(), limits.maxStackFrames); i++) {
            String frame = frames.get(i).toString();
            if (frame.length() > 2048) {
               frame = frame.substring(0, 2048);
               truncated = true;
            }
            stack.add(frame);
         }
         boolean limit = false;
         synchronized (dataLock) {
            if (!accepting) {
               return original;
            }
            SqlEvidence.Event event = new SqlEvidence.Event(nextSequence + 1, System.currentTimeMillis(),
                  request.threadId, request.threadName, request.requestId, request.methodContextId,
                  request.authenticatedUser, request.methodUser, request.requestUri, request.targetClass,
                  request.targetMethod, statement, stack, truncated);
            byte[] encoded = SessionEvidenceStore.encode(event);
            PendingRequest pendingRequest = pending.get(request.requestId);
            boolean newContext = pendingRequest == null
                  || !pendingRequest.completions.containsKey(request.methodContextId);
            if (nextSequence >= limits.maxEvents || reservedBytes + 4L + encoded.length > limits.maxBytes
                  || (newContext && pendingContexts >= limits.maxPendingRequests)) {
               counters[5]++;
               limit = true;
            } else {
               if (pendingRequest == null) {
                  pendingRequest = new PendingRequest();
                  pending.put(request.requestId, pendingRequest);
               }
               if (newContext) {
                  pendingRequest.completions.put(request.methodContextId, request.completion);
                  pendingContexts++;
               }
               pendingRequest.payloads.add(encoded);
               nextSequence++;
               reservedBytes += 4L + encoded.length;
            }
         }
         if (limit) {
            endFromObserver(SqlEvidence.State.LIMIT_REACHED,
                  "The event, byte, or pending-request cap was reached. Tracing stopped; unfinished requests were withheld.");
         }
         return original;
      } catch (Throwable e) {
         endFromObserver(SqlEvidence.State.ERROR, "SQL evidence observation failed (" + e.getClass().getSimpleName() + ").");
         rethrowFatal(e);
         return original;
      } finally {
         insideFilter.remove();
      }
   }

   private static void rethrowFatal(Throwable failure) {
      if (failure instanceof VirtualMachineError) {
         throw (VirtualMachineError) failure;
      }
      if (failure instanceof ThreadDeath) {
         throw (ThreadDeath) failure;
      }
   }

   private static final class PendingRequest {
      private final Map<String, Completion> completions = new LinkedHashMap<String, Completion>();
      private final List<byte[]> payloads = new ArrayList<byte[]>();
   }

   private final class ScopedFilter extends AbstractFilter {
      @Override
      public Result filter(Logger logger, Level level, Marker marker, Object message, Throwable thrown) {
         return observe(logger, level, message);
      }

      @Override
      public Result filter(Logger logger, Level level, Marker marker, Message message, Throwable thrown) {
         return observe(logger, level, message);
      }

      @Override
      public Result filter(Logger logger, Level level, Marker marker, String message, Object... parameters) {
         return observe(logger, level, message == null || parameters == null || parameters.length == 0
               ? message : new ParameterizedMessage(message, parameters));
      }
   }
}
