package com.ptc.dbcapture.diagnostics;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative presentation of saved capture diagnostics, including legacy records. */
public final class CaptureHealth {
   private static final Pattern ORACLE_CODE = Pattern.compile("ORA-0*([0-9]+)");
   private static final Pattern OLD_RECOVERY = Pattern.compile(
         "(?m)^[A-Z][A-Z0-9_$#]*: Flashback version history is unavailable[^\\r\\n]*\\R?"
       + "; recovered net changes by comparing AS OF SCN[^\\r\\n]*"
       + "Intermediate changes, commit SCN/time and transaction ID are unavailable\\.");
   private static final Pattern NEW_RECOVERY = Pattern.compile(
         "Snapshot recovery \\([^\\r\\n]+\\) for [0-9]+ table\\(s\\): [^\\r\\n]+\\R"
       + "Net changes recovered by comparing AS OF SCN[^\\r\\n]+"
       + "Intermediate changes, commit SCN/time and transaction ID are unavailable\\.");
   private static final Pattern HYBRID_RECOVERY = Pattern.compile(
         "Endpoint-ID history recovery \\([^\\r\\n]+\\) for [0-9]+ table\\(s\\): [^\\r\\n]+\\R"
       + "Net-changed row IDs recovered by comparing AS OF SCN[^\\r\\n]+\\R"
       + "Transient-only operations with no endpoint difference may still be unavailable\\.");
   private static final Pattern TRACE_LOSS = Pattern.compile(
         "Request SQL/stack evidence: (?:ERROR|INTERRUPTED|TIMED_OUT|LIMIT_REACHED|ABORTED)\\b"
       + "|(?:unfinished|over limit)=[1-9][0-9]*");

   public enum Tone { GREEN, YELLOW, RED }

   public record Assessment(Tone tone, String label, String summary, String explanation)
         implements Serializable { }

   private CaptureHealth() { }

   public static Assessment assess(String status, String mode, String warnings, String error) {
      String text = warnings == null ? "" : warnings.trim();
      String failure = error == null ? "" : error.trim();
      if ("RUNNING".equals(status)) {
         return result(Tone.YELLOW, "In progress", "Capture is still running.",
               "Results are not final.", "Stop the capture before evaluating completeness.");
      }
      if (!failure.isEmpty() || "FAILED".equals(status) || "ABORTED".equals(status)) {
         return result(Tone.RED, "Failed / incomplete", compact(failure.isEmpty() ? status : failure),
               "Capture did not complete; results are not reliable as a complete record.",
               "Open SQL / Stack Trace for the saved error and capture context.");
      }
      if (!"COMPLETED".equals(status) && !"COMPLETED_WARNINGS".equals(status)) {
         return result(Tone.RED, "Review required", "Unrecognized capture state: " + status,
               "Completeness cannot be confirmed.", "Review the saved capture details.");
      }
      if (text.isEmpty()) {
         if ("COMPLETED".equals(status) && !"FLASHBACK+SNAPSHOT".equals(mode)
               && !"SNAPSHOT".equals(mode)) {
            return new Assessment(Tone.GREEN, "Complete", "",
                  "Collection completed without a recorded warning or error.");
         }
         return result(Tone.RED, "Review required", "Capture diagnostics are missing.",
               "A warning/snapshot state has no saved explanation.",
               "Review the MethodServer log; do not assume complete version history.");
      }
      String remaining = NEW_RECOVERY.matcher(text).replaceAll("");
      remaining = OLD_RECOVERY.matcher(remaining).replaceAll("").trim();
      String beforeHybrid = remaining;
      remaining = HYBRID_RECOVERY.matcher(remaining).replaceAll("").trim();
      boolean hybridRecovered = !remaining.equals(beforeHybrid);
      boolean recovered = !remaining.equals(text);
      boolean traceOnly = !remaining.isEmpty() && remaining.lines().allMatch(line ->
            line.startsWith("Request SQL/stack evidence: ")
            || line.startsWith("Request SQL/stack evidence is unavailable:")
            || line.startsWith("Request SQL/stack capture could not start:"));
      boolean fullScanNotice = !remaining.isEmpty() && remaining.lines().allMatch(line ->
            line.startsWith("Could not read USER_TAB_MODIFICATIONS at start (")
                  && line.endsWith("every table will be examined at stop.")
            || line.equals("Table activity could not be narrowed down; examining every included table."));
      boolean clipped = text.endsWith("...") || text.contains("[Warnings truncated");
      boolean traceLoss = TRACE_LOSS.matcher(text).find();
      if (!clipped && !traceLoss && (remaining.isEmpty() || traceOnly || fullScanNotice)) {
         String cause = hybridRecovered
               ? codes(text) + ": full-table history discovery failed; endpoint IDs were re-read."
               : recovered ? codes(text) + ": version history unavailable; endpoint comparison used."
               : traceOnly ? "SQL/stack coverage warning; see diagnostic details."
               : "Activity lookup unavailable; a full table scan was used.";
         String impact = hybridRecovered
               ? "Usable net changes and endpoint-visible row history/commit metadata were recovered."
               : recovered
               ? "Net row differences recovered; intermediate changes / commit metadata unavailable."
               : traceOnly ? "Saved database differences remain usable; SQL evidence has limited coverage."
               : "No data omission reported; collection may take longer.";
         Tone tone = traceOnly ? Tone.YELLOW : Tone.GREEN;
         return result(tone, hybridRecovered ? "Recovered row history"
               : recovered ? "Usable net changes" : "Complete with notice",
               cause, impact, recovered
                     ? (hybridRecovered
                           ? "Warnings retain the transient-only limitation; full diagnostics are under SQL / Stack Trace."
                           : "Intermediate-only operations remain a limitation; full diagnostics are under SQL / Stack Trace.")
                     : "Open SQL / Stack Trace to inspect the recorded scope and warning.");
      }
      return result(Tone.RED, "Partial / review required",
            clipped ? codes(text) + ": saved warnings are truncated; full impact is unknown."
                  : codes(text) + ": " + compact(remaining),
            "Omission, truncation or an unclassified failure may affect completeness.",
            "Use full diagnostics and the MethodServer log before relying on these results.");
   }

   private static Assessment result(Tone tone, String label, String cause, String impact, String action) {
      return new Assessment(tone, label, compact(cause) + "\n" + compact(impact) + "\n" + compact(action),
            cause + " " + impact + " " + action);
   }

   private static String codes(String text) {
      Set<String> codes = new LinkedHashSet<>();
      Matcher matcher = ORACLE_CODE.matcher(text);
      while (matcher.find()) {
         String digits = matcher.group(1);
         codes.add("ORA-" + "0".repeat(Math.max(0, 5 - digits.length())) + digits);
      }
      return codes.isEmpty() ? "Capture warning" : String.join(", ", codes);
   }

   private static String compact(String text) {
      String single = text.replaceAll("\\s+", " ").trim();
      return single.length() <= 110 ? single : single.substring(0, 107) + "...";
   }
}
