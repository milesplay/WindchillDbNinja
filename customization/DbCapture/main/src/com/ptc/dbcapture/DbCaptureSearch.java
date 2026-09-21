package com.ptc.dbcapture;

import java.util.Locale;

/**
 * The keyword rule for the results page, in one place.
 *
 * Two behaviours, chosen by whether the term has a wildcard in it:
 *
 * <ul>
 *   <li><b>No wildcard</b> - a substring match over everything the row has
 *       stored, which is what the box did before and what people expect from a
 *       plain word. {@code wt} finds {@code WTDOCUMENTMASTERKEY}.</li>
 *   <li><b>{@code *} or {@code ?}</b> - a glob matched against one <i>field
 *       value</i> at a time, so {@code wt*} means "a field that starts with wt"
 *       rather than "a field containing the two characters w, t and then an
 *       asterisk". Before this existed, adding a wildcard <i>removed</i> every
 *       result, because the asterisk was matched literally.</li>
 * </ul>
 *
 * Field-at-a-time is what makes the wildcard worth having. Anchoring the glob
 * against the whole concatenated blob would make every leading {@code *}
 * pointless and every trailing one match everything. The persistables build
 * their searchable text by joining the fields with a newline, so a line is a
 * field; comma separated fields such as objectIdentities are split once more so
 * that a glob can address a single identity.
 *
 * Built once per request. Wildcards use a bounded-memory matcher rather than a
 * backtracking regular expression.
 */
public final class DbCaptureSearch {

   /** Set when the term has no wildcard; the term, already lower cased. */
   private final String plain;
   /** Set when the term has a wildcard; already lower cased. */
   private final String glob;

   private DbCaptureSearch(String plain, String glob) {
      this.plain = plain;
      this.glob = glob;
   }

   /**
    * The matcher for a keyword, or null when there is no keyword and every row
    * qualifies. Callers treat null as "no filter" rather than "matches
    * nothing".
    */
   public static DbCaptureSearch forKeyword(String keyword) {
      if (keyword == null) {
         return null;
      }
      String term = keyword.trim().toLowerCase(Locale.ROOT);
      if (term.isEmpty()) {
         return null;
      }
      if (term.indexOf('*') < 0 && term.indexOf('?') < 0) {
         return new DbCaptureSearch(term, null);
      }
      return new DbCaptureSearch(null, term.replaceAll("\\*+", "*"));
   }

   /** True when the term matches. Null text never matches. */
   public boolean matches(String searchableText) {
      if (searchableText == null) {
         return false;
      }
      searchableText = searchableText.toLowerCase(Locale.ROOT);
      if (plain != null) {
         return searchableText.contains(plain);
      }
      int from = 0;
      int length = searchableText.length();
      while (from <= length) {
         int newline = searchableText.indexOf('\n', from);
         int end = newline < 0 ? length : newline;
         if (matchesField(searchableText.substring(from, end))) {
            return true;
         }
         if (newline < 0) {
            break;
         }
         from = newline + 1;
      }
      return false;
   }

   /** The field itself, then each comma separated part of it. */
   private boolean matchesField(String field) {
      String trimmed = field.trim();
      if (matchesGlob(trimmed)) {
         return true;
      }
      if (trimmed.indexOf(',') < 0) {
         return false;
      }
      for (String part : trimmed.split(",")) {
         if (matchesGlob(part.trim())) {
            return true;
         }
      }
      return false;
   }

   private boolean matchesGlob(String field) {
      int pattern = 0, text = 0, star = -1, retry = 0;
      while (text < field.length()) {
         if (pattern < glob.length() && glob.charAt(pattern) == '*') {
            star = pattern++;
            retry = text;
         } else if (pattern < glob.length()
               && (glob.charAt(pattern) == '?' || glob.codePointAt(pattern) == field.codePointAt(text))) {
            text += Character.charCount(field.codePointAt(text));
            pattern += Character.charCount(glob.codePointAt(pattern));
         } else if (star >= 0 && retry < field.length()) {
            retry += Character.charCount(field.codePointAt(retry));
            text = retry;
            pattern = star + 1;
         } else {
            return false;
         }
      }
      while (pattern < glob.length() && glob.charAt(pattern) == '*') pattern++;
      return pattern == glob.length();
   }
}
