package com.ptc.dbcapture.diagnostics;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes a single DML target, never row ids, bind positions, or SQL literal values.
 * A schema-qualified statement requires an explicitly schema-qualified allowlist entry.
 */
public final class SqlStatementClassifier {
   public static final int MAX_TABLES = 8192;
   private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]{0,127}");
   // SQLDatabasePds's batch executor uses "Delete="/"Update=" instead of the list APIs' " Statement=".
   private static final Pattern NATIVE_PREFIX = Pattern.compile(
         "^(?:Insert|Update|Delete|Merge)(?:\\(Batch\\))? Statement=|^(?:Insert|Update|Delete)=|^EXECUTE:\\s*",
         Pattern.CASE_INSENSITIVE);
   private final Set<String> tables;

   public SqlStatementClassifier(Collection<String> tables) {
      if (tables == null || tables.isEmpty() || tables.size() > MAX_TABLES) {
         throw new IllegalArgumentException("Supply 1 to " + MAX_TABLES + " explicit physical tables");
      }
      Set<String> normalized = new LinkedHashSet<String>();
      for (String table : tables) {
         String[] components = table == null ? new String[0] : table.split("\\.", -1);
         if (components.length < 1 || components.length > 2
               || !IDENTIFIER.matcher(components[0]).matches()
               || (components.length == 2 && !IDENTIFIER.matcher(components[1]).matches())) {
            throw new IllegalArgumentException("A physical table name, not a pattern or SQL expression, is required");
         }
         String name = table.toUpperCase(Locale.ROOT);
         if (isDiagnosticTable(components[components.length - 1].toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Diagnostic tables cannot be monitored");
         }
         normalized.add(name);
      }
      this.tables = Collections.unmodifiableSet(normalized);
   }

   public Set<String> getTables() {
      return tables;
   }

   public SqlEvidence.Statement classify(String nativeMessage) {
      if (nativeMessage == null) {
         return null;
      }
      String text = sqlText(nativeMessage);
      Cursor cursor = new Cursor(text);
      String operation = cursor.word();
      if ("INSERT".equals(operation) || "MERGE".equals(operation)) {
         if (!"INTO".equals(cursor.word())) {
            return null;
         }
      } else if ("DELETE".equals(operation)) {
         if (!"FROM".equals(cursor.word())) {
            return null;
         }
      } else if (!"UPDATE".equals(operation)) {
         return null;
      }

      String table = cursor.identifier();
      if (table == null) {
         return null;
      }
      if (cursor.take('.')) {
         String leaf = cursor.identifier();
         if (leaf == null || cursor.take('.')) {
            return null;
         }
         table = table + "." + leaf;
      }
      if (cursor.take('@') || !tables.contains(table)) {
         return null;
      }
      return new SqlEvidence.Statement(operation, table, nativeMessage);
   }

   static String sqlText(String nativeMessage) {
      String text = nativeMessage.stripLeading();
      Matcher prefix = NATIVE_PREFIX.matcher(text);
      return prefix.find() ? text.substring(prefix.end()) : text;
   }

   public static boolean isDiagnosticTable(String table) {
      return table.startsWith("DBCAPTURE") || table.equals("MISCLOGEVENTS")
            || table.equals("METHODCONTEXTS") || table.equals("SERVLETREQUESTS")
            || table.equals("LOGTABLE") || table.equals("DUAL")
            || table.startsWith("V$") || table.startsWith("GV$")
            || table.startsWith("DBA_") || table.startsWith("ALL_")
            || table.startsWith("USER_");
   }

   private static final class Cursor {
      private final String text;
      private int offset;

      private Cursor(String text) {
         this.text = text;
      }

      private boolean skip() {
         while (offset < text.length()) {
            char current = text.charAt(offset);
            if (Character.isWhitespace(current)) {
               offset++;
            } else if (text.startsWith("/*", offset)) {
               int end = text.indexOf("*/", offset + 2);
               if (end < 0) {
                  return false;
               }
               offset = end + 2;
            } else if (text.startsWith("--", offset)) {
               int end = text.indexOf('\n', offset + 2);
               if (end < 0) {
                  return false;
               }
               offset = end + 1;
            } else {
               return true;
            }
         }
         return true;
      }

      private String word() {
         if (!skip() || offset >= text.length() || text.charAt(offset) == '"') {
            return null;
         }
         return unquoted();
      }

      private String identifier() {
         if (!skip() || offset >= text.length()) {
            return null;
         }
         if (text.charAt(offset) != '"') {
            return unquoted();
         }
         int end = text.indexOf('"', ++offset);
         if (end < 0) {
            return null;
         }
         String value = text.substring(offset, end);
         offset = end + 1;
         // Lowercase quoted identifiers may name a different Oracle table: do not fold them.
         return (offset == text.length() || text.charAt(offset) != '"')
               && IDENTIFIER.matcher(value).matches() && value.equals(value.toUpperCase(Locale.ROOT))
               ? value : null;
      }

      private String unquoted() {
         int start = offset;
         while (offset < text.length()) {
            char c = text.charAt(offset);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                  && !(c >= '0' && c <= '9') && c != '_' && c != '$' && c != '#') {
               break;
            }
            offset++;
         }
         String value = text.substring(start, offset);
         return IDENTIFIER.matcher(value).matches() ? value.toUpperCase(Locale.ROOT) : null;
      }

      private boolean take(char c) {
         if (skip() && offset < text.length() && text.charAt(offset) == c) {
            offset++;
            return true;
         }
         return false;
      }
   }
}
