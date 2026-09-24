package com.custom.dbcapture.diagnostics;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bounded, non-serialized private evidence files addressed by the persistent session OID.
 * The root must be local, server-owned, outside web-served directories, and mode 0700.
 * Callers must authorize access to the session before reading or deleting evidence.
 * An absent file on another MethodServer means unavailable evidence, not an empty result.
 */
@SuppressWarnings("try")
public final class SessionEvidenceStore {
   private static final Pattern OID = Pattern.compile(
      "(?:OR:)?com\\.custom\\.dbcapture\\.DbCaptureSession:[1-9][0-9]{0,18}");
   private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
   private static final boolean WINDOWS = System.getProperty("os.name", "")
      .toLowerCase(Locale.ROOT).contains("windows");
   private static final Set<PosixFilePermission> DIRECTORY_MODE =
         PosixFilePermissions.fromString("rwx------");
   private static final Set<PosixFilePermission> FILE_MODE =
         PosixFilePermissions.fromString("rw-------");
   private static final Set<AclEntryPermission> ACCESS_PERMISSIONS = EnumSet.allOf(AclEntryPermission.class);
   private static final int MAGIC = 0x44424345;
   private static final int VERSION = 1;
   private static final int PRIVATE_OPEN_RETRIES = 8;
   private static final int MAX_METADATA_BYTES = 262_144;
   private static final int MAX_CATALOG_TABLES = 32_768;
   private static final int MAX_STRING_BYTES = 262_144;
   static final int MAX_RECORD_BYTES = 1_048_576;
   private static final long STORE_BUDGET = 512L * 1024L * 1024L;
   private final Path root;

   public SessionEvidenceStore(Path root) throws IOException {
      if (root == null) {
         throw new IllegalArgumentException("A private evidence root is required");
      }
      this.root = root.toAbsolutePath().normalize();
      for (Path component : this.root) {
         String name = component.toString();
         if ("codebase".equalsIgnoreCase(name) || "src_web".equalsIgnoreCase(name)
               || "webapps".equalsIgnoreCase(name) || "htdocs".equalsIgnoreCase(name)) {
            throw new IOException("Evidence must not be stored in a web-served directory");
         }
      }
      rejectSymlinkAncestors(this.root);
      if (Files.exists(this.root, LinkOption.NOFOLLOW_LINKS)) {
         checkPrivate(this.root, true);
      }
   }

   public static String canonicalSessionOid(String sessionOid) {
      if (sessionOid == null || !OID.matcher(sessionOid).matches()) {
         throw new IllegalArgumentException("A persisted DbCaptureSession object OID is required, not a capture ID");
      }
      return sessionOid.startsWith("OR:") ? sessionOid.substring(3) : sessionOid;
   }

   public SqlEvidence.Snapshot read(String sessionOid) throws IOException {
      String oid = canonicalSessionOid(sessionOid);
      rejectSymlinkAncestors(root);
      Path directory = directory(oid);
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
         return SqlEvidence.Snapshot.unavailable(oid,
               "No evidence file for this session OID on this MethodServer. Capture may be disabled, "
             + "unavailable, deleted, or stored on a different server; absence is not proof of no SQL.");
      }
      checkPrivate(root, true);
      checkPrivate(directory, true);
      Properties metadata = readMetadata(directory);
      if (!oid.equals(metadata.getProperty("sessionOid"))) {
         throw new IOException("Evidence session identity does not match");
      }
      long count = number(metadata, "eventCount", 0, SqlEvidence.Limits.MAX_EVENTS);
      long bytes = number(metadata, "eventBytes", 0, SqlEvidence.Limits.MAX_BYTES);
      long maxBytes = number(metadata, "maxBytes", 4096, SqlEvidence.Limits.MAX_BYTES);
      if (bytes > maxBytes) {
         throw new IOException("Evidence exceeds its declared byte limit");
      }
      Path eventsFile = directory.resolve("events.bin");
      checkPrivate(eventsFile, false);
      if (Files.size(eventsFile) > maxBytes || Files.size(eventsFile) < bytes) {
         throw new IOException("Evidence data length is invalid");
      }
      List<SqlEvidence.Event> events = new ArrayList<SqlEvidence.Event>();
      Set<Long> sequences = new HashSet<Long>();
      try (InputStream input = openPrivateInput(eventsFile);
           DataInputStream data = new DataInputStream(input)) {
         if (data.readInt() != MAGIC || data.readInt() != VERSION || !oid.equals(readString(data))) {
            throw new IOException("Unsupported or mismatched evidence header");
         }
         long consumed = 12L + oid.getBytes(StandardCharsets.UTF_8).length;
         for (long i = 0; i < count; i++) {
            int length = data.readInt();
            if (length < 1 || length > MAX_RECORD_BYTES || consumed + 4L + length > bytes) {
               throw new IOException("Invalid evidence record length");
            }
            byte[] payload = data.readNBytes(length);
            if (payload.length != length) {
               throw new IOException("Incomplete evidence record");
            }
            SqlEvidence.Event event = decode(payload);
            if (event.getSequence() < 1 || !sequences.add(event.getSequence())) {
               throw new IOException("Invalid evidence event sequence");
            }
            events.add(event);
            consumed += 4L + length;
         }
         if (consumed != bytes) {
            throw new IOException("Evidence checkpoint does not match its records");
         }
      }
      events.sort(Comparator.comparingLong(SqlEvidence.Event::getSequence));
      SqlEvidence.State state;
      try {
         state = SqlEvidence.State.valueOf(metadata.getProperty("state", ""));
      } catch (IllegalArgumentException e) {
         throw new IOException("Invalid evidence state", e);
      }
      String reason = metadata.getProperty("reason", "");
      if (state == SqlEvidence.State.ACTIVE && !isLeased(directory)) {
         state = SqlEvidence.State.INTERRUPTED;
         reason = "The evidence writer exited without closing its window. Only the last durable checkpoint "
               + "is shown; pending and uncheckpointed events are unavailable.";
      }
      List<String> tables;
      try {
         String recorded = metadata.getProperty("tables");
         if (recorded == null) throw new IllegalArgumentException("Missing recorded table scope");
         tables = recorded.isEmpty() ? List.of()
               : List.copyOf(new SqlStatementClassifier(List.of(recorded.split(",", -1))).getTables());
         if (tables.isEmpty() && !events.isEmpty()) {
            throw new IllegalArgumentException("SQL evidence cannot have an empty recorded table scope");
         }
      } catch (IllegalArgumentException e) {
         throw new IOException("Invalid recorded SQL table scope", e);
      }
      List<String> notRecordedTables = readNotRecordedTables(metadata, tables);
      return new SqlEvidence.Snapshot(oid, state, reason, metadata.getProperty("node", ""),
            number(metadata, "started", 0, Long.MAX_VALUE),
            number(metadata, "deadline", 0, Long.MAX_VALUE),
            number(metadata, "finished", 0, Long.MAX_VALUE),
            number(metadata, "observed", 0, Long.MAX_VALUE),
            number(metadata, "filtered", 0, Long.MAX_VALUE),
            number(metadata, "contextRejected", 0, Long.MAX_VALUE),
            number(metadata, "requestRejected", 0, Long.MAX_VALUE),
            number(metadata, "unfinished", 0, Long.MAX_VALUE),
            number(metadata, "limitDiscarded", 0, Long.MAX_VALUE), events, tables, notRecordedTables);
   }

   /** Refuses deletion while a writer owns this OID. No other session is touched. */
   public void delete(String sessionOid) throws IOException {
      String oid = canonicalSessionOid(sessionOid);
      rejectSymlinkAncestors(root);
      Path directory = directory(oid);
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
         return;
      }
      checkPrivate(root, true);
      checkPrivate(directory, true);
      try (Lease rootLease = rootLease()) {
         Lease sessionLease = lease(directory.resolve("lease.lock"), false);
         List<Path> files = new ArrayList<Path>();
         try {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
               for (Path entry : entries) {
                  String name = entry.getFileName().toString();
                  if (!name.equals("events.bin") && !name.equals("metadata.properties")
                        && !name.equals("lease.lock")
                        && !name.matches("metadata\\.[0-9a-f-]{36}\\.new")) {
                     throw new IOException("Unexpected file in the evidence session directory");
                  }
                  checkPrivate(entry, false);
                  files.add(entry);
               }
            }
            for (Path file : files) {
               if (!file.getFileName().toString().equals("lease.lock")) {
                  Files.delete(file);
               }
            }
         } finally {
            sessionLease.close();
         }
         Path leaseFile = directory.resolve("lease.lock");
         checkPrivate(leaseFile, false);
         Files.delete(leaseFile);
         Files.delete(directory);
      }
   }

   Writer begin(String sessionOid, SqlEvidence.Limits limits, Set<String> tables) throws IOException {
      return begin(sessionOid, limits, tables, null);
   }

   Writer begin(String sessionOid, SqlEvidence.Limits limits, Set<String> tables,
                Collection<String> tableCatalog) throws IOException {
      String oid = canonicalSessionOid(sessionOid);
      List<String> notRecordedTables = frozenComplement(tables, tableCatalog);
      rejectSymlinkAncestors(root);
      createPrivateDirectories(root);
      checkPrivate(root, true);
      try (Lease rootLease = rootLease()) {
         checkBudget(limits.maxBytes);
         Path directory = directory(oid);
         createPrivateDirectory(directory);
         Lease sessionLease = null;
         FileChannel events = null;
         try {
            sessionLease = lease(directory.resolve("lease.lock"), true);
            events = createFile(directory.resolve("events.bin"));
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(header)) {
               data.writeInt(MAGIC);
               data.writeInt(VERSION);
               writeString(data, oid);
            }
            writeFully(events, header.toByteArray());
            Writer writer = new Writer(directory, oid, limits, tables, notRecordedTables, sessionLease, events);
            writer.checkpoint(SqlEvidence.State.ACTIVE, "Window open; completed request evidence only.",
                  new long[6], 0);
            return writer;
         } catch (IOException | RuntimeException e) {
            if (events != null) {
               try { events.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            }
            if (sessionLease != null) {
               try { sessionLease.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            }
            throw e;
         }
      }
   }

   private static List<String> frozenComplement(Set<String> tables, Collection<String> tableCatalog) {
      if (tableCatalog == null) return null;
      if (tableCatalog.size() > MAX_CATALOG_TABLES) {
         throw new IllegalArgumentException("Start-time table catalog exceeds the supported limit");
      }
      Set<String> remaining = new TreeSet<>();
      for (String table : tableCatalog) {
         if (!validCatalogTable(table)) {
            throw new IllegalArgumentException("Invalid Start-time table catalog name");
         }
         remaining.add(table);
      }
      if (!remaining.containsAll(tables)) {
         throw new IllegalArgumentException("Start-time table catalog does not contain the recorded scope");
      }
      remaining.removeAll(tables);
      return List.copyOf(remaining);
   }

   private static boolean validCatalogTable(String table) {
      return table != null && !table.isEmpty() && table.length() <= 257 && table.indexOf('\0') < 0;
   }

   private static List<String> readNotRecordedTables(Properties metadata, List<String> recorded) throws IOException {
      if (!metadata.containsKey("notRecordedTableCount")) return null;
      int count = (int) number(metadata, "notRecordedTableCount", 0, MAX_CATALOG_TABLES);
      Set<String> tables = new TreeSet<>();
      Set<String> included = new HashSet<>(recorded);
      for (int i = 0; i < count; i++) {
         String table = metadata.getProperty("notRecordedTable." + i);
         if (!validCatalogTable(table) || included.contains(table) || !tables.add(table)) {
            throw new IOException("Invalid frozen not-recorded table scope");
         }
      }
      return List.copyOf(tables);
   }

   private void checkBudget(long reservation) throws IOException {
      long total = reservation;
      int sessions = 0;
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
         for (Path entry : entries) {
            if (KEY.matcher(entry.getFileName().toString()).matches()) {
               checkPrivate(entry, true);
               if (++sessions >= 256) {
                  throw new IOException("Evidence session retention limit reached; delete old session evidence");
               }
               Path metadata = entry.resolve("metadata.properties");
               total += Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)
                     ? number(readMetadata(entry), "maxBytes", 4096, SqlEvidence.Limits.MAX_BYTES)
                     : SqlEvidence.Limits.MAX_BYTES;
               if (total > STORE_BUDGET) {
                  throw new IOException("Evidence storage reservation limit reached; delete old session evidence");
               }
            }
         }
      }
   }

   private Path directory(String oid) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(oid.getBytes(StandardCharsets.UTF_8));
         StringBuilder key = new StringBuilder(64);
         for (byte b : digest) {
            key.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
         }
         return root.resolve(key.toString());
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException("SHA-256 is required by Java", e);
      }
   }

   private Lease rootLease() throws IOException {
      Path file = root.resolve("store.lock");
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
         try (FileChannel ignored = createFile(file)) {
            // The first creator establishes private permissions.
         } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another process created the same lock file.
         }
      }
      return lease(file, false);
   }

   private static Lease lease(Path file, boolean create) throws IOException {
      FileChannel channel = create ? createFile(file) : openLockFile(file);
      try {
         FileLock lock = channel.tryLock();
         if (lock == null) {
            throw new IOException("Evidence is currently in use");
         }
         return new Lease(channel, lock);
      } catch (IOException | RuntimeException e) {
         channel.close();
         if (e instanceof OverlappingFileLockException) {
            throw new IOException("Evidence is currently in use", e);
         }
         throw e;
      }
   }

   private static boolean isLeased(Path directory) throws IOException {
      try (FileChannel channel = openLockFile(directory.resolve("lease.lock"))) {
         try (FileLock lock = channel.tryLock()) {
            return lock == null;
         } catch (OverlappingFileLockException e) {
            return true;
         }
      }
   }

   private static FileChannel openLockFile(Path file) throws IOException {
      checkPrivate(file, false);
      Object before = fileIdentity(file);
      FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      try {
         if (!before.equals(fileIdentity(file))) {
            throw new IOException("Evidence file identity changed while opening");
         }
         return channel;
      } catch (IOException | RuntimeException failure) {
         channel.close();
         throw failure;
      }
   }

   private static FileChannel createFile(Path path) throws IOException {
      Set<java.nio.file.OpenOption> options = new HashSet<java.nio.file.OpenOption>();
      Collections.addAll(options, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
      FileChannel channel = WINDOWS
            ? FileChannel.open(path, options)
            : FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(FILE_MODE));
      try {
         if (WINDOWS) setWindowsAcl(path, false);
         checkPrivate(path, false);
         return channel;
      } catch (IOException | RuntimeException failure) {
         channel.close();
         Files.deleteIfExists(path);
         throw failure;
      }
   }

   private static void checkPrivate(Path path, boolean directory) throws IOException {
      if (Files.isSymbolicLink(path) || (directory
            ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
         throw new IOException("Evidence paths must be ordinary files and directories, never symbolic links");
      }
      if (WINDOWS) {
         checkWindowsPrivate(path);
      } else {
         if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
               .equals(directory ? DIRECTORY_MODE : FILE_MODE)
               || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName()
                     .equals(System.getProperty("user.name"))) {
            throw new IOException("Evidence files must be owned by the server account with private permissions");
         }
         if (!directory && ((Number) Files.getAttribute(path, "unix:nlink",
               LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
            throw new IOException("Hard-linked evidence files are not supported");
         }
      }
   }

   private static void rejectSymlinkAncestors(Path path) throws IOException {
      for (Path current = path; current != null; current = current.getParent()) {
         if (Files.isSymbolicLink(current)) {
            throw new IOException("Symbolic links are not allowed in the evidence path");
         }
         if (WINDOWS && Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            String requested = current.toAbsolutePath().normalize().toString();
            String actual = current.toRealPath().toString();
            if (!requested.equalsIgnoreCase(actual)) {
               throw new IOException("Reparse points are not allowed in the evidence path");
            }
         }
      }
   }

   private static void createPrivateDirectories(Path directory) throws IOException {
      if (WINDOWS) {
         Path existing = directory;
         while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
         }
         requireLocalNtfs(existing);
         Files.createDirectories(directory);
         setWindowsAcl(directory, true);
      } else {
         Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
      }
   }

   private static void createPrivateDirectory(Path directory) throws IOException {
      if (WINDOWS) {
         Files.createDirectory(directory);
         setWindowsAcl(directory, true);
      } else {
         Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
      }
      checkPrivate(directory, true);
   }

   private static void requireLocalNtfs(Path path) throws IOException {
      if (path == null || path.toString().startsWith("\\\\")
            || !"NTFS".equalsIgnoreCase(Files.getFileStore(path).type())) {
         throw new IOException("Windows evidence storage requires a local NTFS volume");
      }
   }

   private static void setWindowsAcl(Path path, boolean directory) throws IOException {
      AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
      if (view == null) throw new IOException("Windows evidence storage requires ACL support");
      UserPrincipal owner = lookupCurrentPrincipal(path);
      view.setOwner(owner);
      List<AclEntry> acl = new ArrayList<AclEntry>();
      addFullControl(acl, owner, directory);
      addWellKnownFullControl(path, acl, "NT AUTHORITY\\SYSTEM", directory);
      addWellKnownFullControl(path, acl, "BUILTIN\\Administrators", directory);
      view.setAcl(acl);
   }

   private static void addWellKnownFullControl(Path path, List<AclEntry> acl, String accountName,
                                               boolean directory) throws IOException {
      try {
         UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
         addFullControl(acl, lookup.lookupPrincipalByName(accountName), directory);
      } catch (java.nio.file.attribute.UserPrincipalNotFoundException ignored) {
         // Some localized Windows providers do not resolve well-known SID strings.
      }
   }

   private static void addFullControl(List<AclEntry> acl, UserPrincipal principal, boolean directory) {
      AclEntry.Builder builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
            .setPrincipal(principal).setPermissions(EnumSet.allOf(AclEntryPermission.class));
      if (directory) builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
      acl.add(builder.build());
   }

   private static UserPrincipal currentPrincipal(Path path) throws IOException {
      UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
      UserPrincipal current = lookupCurrentPrincipal(path);
      if (!owner.equals(current)) {
         throw new IOException("Evidence files must be owned by the Windchill service account");
      }
      return owner;
   }

   private static UserPrincipal lookupCurrentPrincipal(Path path) throws IOException {
      return path.getFileSystem().getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"));
   }

   private static void checkWindowsPrivate(Path path) throws IOException {
      requireLocalNtfs(path);
      AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
      if (view == null) throw new IOException("Windows evidence storage requires ACL support");
      UserPrincipal owner = currentPrincipal(path);
      Set<UserPrincipal> allowed = new HashSet<UserPrincipal>();
      allowed.add(owner);
      for (String accountName : List.of("NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators")) {
         try {
            allowed.add(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(accountName));
         } catch (java.nio.file.attribute.UserPrincipalNotFoundException ignored) {
            // Unresolvable well-known principals cannot authorize another ACE.
         }
      }
      for (AclEntry entry : view.getAcl()) {
         if (entry.type() == AclEntryType.ALLOW
               && !Collections.disjoint(entry.permissions(), ACCESS_PERMISSIONS)
               && !allowed.contains(entry.principal())) {
            throw new IOException("Evidence ACL grants access outside the service account, SYSTEM or Administrators");
         }
      }
      fileIdentity(path);
   }

   private static Object fileIdentity(Path path) throws IOException {
      BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
         LinkOption.NOFOLLOW_LINKS);
      Object key = attributes.fileKey();
      if (key != null) return key;
      if (WINDOWS) {
         return path.toRealPath(LinkOption.NOFOLLOW_LINKS).toString().toLowerCase(Locale.ROOT)
            + '|' + attributes.creationTime().toMillis() + '|'
            + (attributes.isDirectory() ? 'd' : attributes.isRegularFile() ? 'f' : 'o');
      }
      throw new IOException("Evidence filesystem does not expose stable file identity");
   }

   private static InputStream openPrivateInput(Path file) throws IOException {
      IOException lastRace = null;
      for (int attempt = 0; attempt < PRIVATE_OPEN_RETRIES; attempt++) {
         try {
            checkPrivate(file, false);
            Object before = fileIdentity(file);
            InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
            boolean retry = false;
            try {
               checkPrivate(file, false);
               if (before.equals(fileIdentity(file))) return input;
               lastRace = new IOException("Evidence file identity changed while opening");
               retry = true;
            } catch (IOException e) {
               if (!missingWithoutFollowingLinks(file)) throw e;
               lastRace = e;
               retry = true;
            }
            input.close();
            if (retry) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
         } catch (IOException | RuntimeException failure) {
            if (!missingWithoutFollowingLinks(file)) throw failure;
            lastRace = failure instanceof IOException
                  ? (IOException) failure : new IOException("Evidence file disappeared while opening", failure);
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
         }
      }
      throw new IOException("Evidence file changed repeatedly while opening", lastRace);
   }

   private static boolean missingWithoutFollowingLinks(Path file) throws IOException {
      try {
         Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return false;
      } catch (java.nio.file.NoSuchFileException e) {
         return true;
      }
   }

   private static Properties readMetadata(Path directory) throws IOException {
      Path file = directory.resolve("metadata.properties");
      checkPrivate(file, false);
      if (Files.size(file) > MAX_METADATA_BYTES) {
         throw new IOException("Evidence metadata exceeds the size limit");
      }
      Properties result = new Properties();
      try (InputStream input = openPrivateInput(file)) {
         byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
         if (bytes.length > MAX_METADATA_BYTES) {
            throw new IOException("Evidence metadata exceeds the size limit");
         }
         result.load(new ByteArrayInputStream(bytes));
      } catch (IllegalArgumentException e) {
         throw new IOException("Evidence metadata is malformed", e);
      }
      if (!Integer.toString(VERSION).equals(result.getProperty("version"))) {
         throw new IOException("Unsupported evidence metadata version");
      }
      return result;
   }

   private static long number(Properties properties, String key, long minimum, long maximum) throws IOException {
      try {
         long value = Long.parseLong(properties.getProperty(key, ""));
         if (value < minimum || value > maximum) {
            throw new NumberFormatException();
         }
         return value;
      } catch (NumberFormatException e) {
         throw new IOException("Invalid evidence metadata field: " + key, e);
      }
   }

   static byte[] encode(SqlEvidence.Event event) throws IOException {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (DataOutputStream data = new DataOutputStream(output)) {
         data.writeLong(event.getSequence());
         data.writeLong(event.getTimestampMillis());
         data.writeLong(event.getThreadId());
         writeString(data, event.getThreadName());
         writeString(data, event.getServletRequestId());
         writeString(data, event.getMethodContextId());
         writeString(data, event.getAuthenticatedUser());
         writeString(data, event.getMethodUser());
         writeString(data, event.getRequestUri());
         writeString(data, event.getTargetClass());
         writeString(data, event.getTargetMethod());
         writeString(data, event.getStatement().getOperation());
         writeString(data, event.getStatement().getTable());
         writeString(data, event.getStatement().getNativeMessage());
         data.writeBoolean(event.isStackTruncated());
         data.writeInt(event.getStack().size());
         for (String frame : event.getStack()) {
            writeString(data, frame);
         }
      }
      byte[] bytes = output.toByteArray();
      if (bytes.length > MAX_RECORD_BYTES) {
         throw new IOException("Evidence record exceeds the size limit");
      }
      return bytes;
   }

   private static SqlEvidence.Event decode(byte[] bytes) throws IOException {
      try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
         long sequence = data.readLong();
         long timestamp = data.readLong();
         long threadId = data.readLong();
         String threadName = readString(data);
         String requestId = readString(data);
         String contextId = readString(data);
         String user = readString(data);
         String methodUser = readString(data);
         String uri = readString(data);
         String targetClass = readString(data);
         String targetMethod = readString(data);
         String operation = readString(data);
         String table = readString(data);
         String message = readString(data);
         boolean truncated = data.readBoolean();
         int length = data.readInt();
         if (length < 0 || length > 256 || !operation.matches("INSERT|UPDATE|DELETE|MERGE")) {
            throw new IOException("Invalid SQL evidence record");
         }
         List<String> frames = new ArrayList<String>();
         for (int i = 0; i < length; i++) {
            frames.add(readString(data));
         }
         if (data.available() != 0) {
            throw new IOException("Unexpected trailing evidence record data");
         }
         return new SqlEvidence.Event(sequence, timestamp, threadId, threadName, requestId, contextId,
               user, methodUser, uri, targetClass, targetMethod,
               new SqlEvidence.Statement(operation, table, message), frames, truncated);
      }
   }

   private static void writeString(DataOutputStream data, String value) throws IOException {
      byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
      if (bytes.length > MAX_STRING_BYTES) {
         throw new IOException("Evidence string exceeds the size limit");
      }
      data.writeInt(bytes.length);
      data.write(bytes);
   }

   private static String readString(DataInputStream data) throws IOException {
      int length = data.readInt();
      if (length < 0 || length > MAX_STRING_BYTES) {
         throw new IOException("Invalid evidence string length");
      }
      byte[] bytes = data.readNBytes(length);
      if (bytes.length != length) {
         throw new IOException("Incomplete evidence string");
      }
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
   }

   private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
         channel.write(buffer);
      }
   }

   private static final class Lease implements AutoCloseable {
      private final FileChannel channel;
      private final FileLock lock;

      private Lease(FileChannel channel, FileLock lock) {
         this.channel = channel;
         this.lock = lock;
      }

      @Override
      public void close() throws IOException {
         try {
            lock.release();
         } finally {
            channel.close();
         }
      }
   }

   static final class Writer implements AutoCloseable {
      private final Path directory;
      private final Lease lease;
      private final FileChannel events;
      private final Properties metadata = new Properties();
      private final long maxBytes;
      final long startedMillis = System.currentTimeMillis();
      final long deadlineMillis;
      private long count;
      private boolean closed;

      private Writer(Path directory, String oid, SqlEvidence.Limits limits, Set<String> tables,
                     List<String> notRecordedTables,
                     Lease lease, FileChannel events) {
         this.directory = directory;
         this.lease = lease;
         this.events = events;
         this.maxBytes = limits.maxBytes;
         this.deadlineMillis = startedMillis + limits.durationMillis;
         metadata.setProperty("version", Integer.toString(VERSION));
         metadata.setProperty("sessionOid", oid);
         metadata.setProperty("node", ManagementFactory.getRuntimeMXBean().getName());
         metadata.setProperty("started", Long.toString(startedMillis));
         metadata.setProperty("deadline", Long.toString(deadlineMillis));
         metadata.setProperty("maxBytes", Long.toString(maxBytes));
         metadata.setProperty("tables", String.join(",", tables));
         if (notRecordedTables != null) {
            metadata.setProperty("notRecordedTableCount", Integer.toString(notRecordedTables.size()));
            for (int i = 0; i < notRecordedTables.size(); i++) {
               metadata.setProperty("notRecordedTable." + i, notRecordedTables.get(i));
            }
         }
         metadata.setProperty("source", SqlEvidence.SOURCE);
         metadata.setProperty("attribution", SqlEvidence.ATTRIBUTION);
      }

      long getBytes() throws IOException {
         return events.position();
      }

      void append(byte[] payload) throws IOException {
         if (closed || payload.length > MAX_RECORD_BYTES || events.position() + 4L + payload.length > maxBytes) {
            throw new IOException("Evidence file limit reached or writer closed");
         }
         writeFully(events, ByteBuffer.allocate(4).putInt(payload.length).array());
         writeFully(events, payload);
         count++;
      }

      void checkpoint(SqlEvidence.State state, String reason, long[] counters, long finished) throws IOException {
         if (closed) {
            throw new IOException("Evidence writer is closed");
         }
         checkPrivate(directory, true);
         events.force(false);
         metadata.setProperty("state", state.name());
         metadata.setProperty("reason", reason);
         metadata.setProperty("finished", Long.toString(finished));
         metadata.setProperty("eventCount", Long.toString(count));
         metadata.setProperty("eventBytes", Long.toString(events.position()));
         String[] names = { "observed", "filtered", "contextRejected", "requestRejected", "unfinished", "limitDiscarded" };
         for (int i = 0; i < names.length; i++) {
            metadata.setProperty(names[i], Long.toString(counters[i]));
         }
         ByteArrayOutputStream encoded = new ByteArrayOutputStream();
         metadata.store(encoded, "DB Capture private evidence; not a transaction audit");
         if (encoded.size() > MAX_METADATA_BYTES) {
            throw new IOException("Evidence metadata exceeds the size limit");
         }
         Path staged = directory.resolve("metadata." + UUID.randomUUID() + ".new");
         try {
            try (FileChannel channel = createFile(staged)) {
               writeFully(channel, encoded.toByteArray());
               channel.force(true);
            }
            Path target = directory.resolve("metadata.properties");
            try {
               Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                     StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | java.nio.file.AccessDeniedException e) {
               if (!WINDOWS) throw e;
               checkPrivate(staged, false);
               if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                  checkPrivate(target, false);
                  fileIdentity(target);
               }
               Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
               checkPrivate(target, false);
            }
         } finally {
            Files.deleteIfExists(staged);
         }
      }

      @Override
      public void close() throws IOException {
         if (!closed) {
            closed = true;
            try {
               events.close();
            } finally {
               lease.close();
            }
         }
      }
   }
}
