(function () {
   "use strict";
   var requestStates = new WeakMap();
   document.querySelectorAll("details.request").forEach(function (request) {
      requestStates.set(request, request.open);
   });
   function feedback(message, scope, selector) {
      var status = scope && scope.querySelector(selector || ".sql-feedback")
         || document.getElementById("diagnostic-feedback");
      if (status) status.textContent = message;
   }
   function requestDescendants(request) {
      requestStates.set(request, request.open);
      request.querySelectorAll("details").forEach(function (detail) {
         detail.open = request.open;
      });
   }
   document.addEventListener("toggle", function (event) {
      var request = event.target;
      if (request.matches && request.matches("details.request")
            && requestStates.get(request) !== request.open) requestDescendants(request);
   }, true);
   function openAncestors(card) {
      for (var parent = card.parentElement; parent; parent = parent.parentElement) {
         if (parent.tagName === "DETAILS" && !parent.open) {
            parent.open = true;
            if (parent.matches("details.request")) requestDescendants(parent);
         }
      }
   }
   function showStatement(card, navigate) {
      document.querySelectorAll(".statement-card.selected").forEach(function (selected) {
         selected.classList.remove("selected");
      });
      openAncestors(card);
      card.open = true;
      card.classList.add("selected");
      if (navigate) {
         card.querySelector("summary").focus({ preventScroll: true });
         card.scrollIntoView({ block: "nearest", inline: "nearest" });
         feedback("");
      }
   }
   function legacyCopy(text) {
      var field = document.createElement("textarea");
      var active = document.activeElement;
      var selection = window.getSelection && window.getSelection();
      var ranges = [];
      var start = active && active.selectionStart;
      var end = active && active.selectionEnd;
      var direction = active && active.selectionDirection;
      if (selection) {
         for (var i = 0; i < selection.rangeCount; i++) ranges.push(selection.getRangeAt(i).cloneRange());
      }
      field.value = text;
      field.readOnly = true;
      field.tabIndex = -1;
      field.setAttribute("aria-label", "Plain text clipboard copy");
      field.style.cssText = "position:fixed;left:-10000px;top:0;width:1px;height:1px;";
      try {
         document.body.appendChild(field);
         field.focus({ preventScroll: true });
         field.select();
         field.setSelectionRange(0, text.length);
         return typeof document.execCommand === "function" && document.execCommand("copy") === true;
      } finally {
         field.remove();
         // Restoring focus/selection must not misreport a successfully completed copy.
         try {
            if (active && active.focus) active.focus({ preventScroll: true });
            if (active && typeof start === "number" && active.setSelectionRange) {
               active.setSelectionRange(start, end, direction);
            } else if (selection) {
               selection.removeAllRanges();
               ranges.forEach(function (range) { selection.addRange(range); });
            }
         } catch (ignored) { /* The previously focused element may no longer exist. */ }
      }
   }
   function copyText(button, scope, sourceSelector, statusSelector, label) {
      if (button.disabled) return;
      var source = scope && scope.querySelector(sourceSelector);
      if (!source || !source.textContent) {
         feedback(label + " text is unavailable; nothing was copied.", scope, statusSelector);
         return;
      }
      var text = source.textContent;
      button.disabled = true;
      feedback("");
      feedback("Copying " + label + "\u2026", scope, statusSelector);
      function finish(copied) {
         button.disabled = false;
         feedback(copied ? label + " copied to clipboard."
               + (label === "SQL" ? " Placeholders are unchanged." : "")
            : "Could not copy " + label
               + ": clipboard access was denied or failed. Select and copy the displayed text manually.",
            scope, statusSelector);
      }
      function fallback() {
         var copied = false;
         try { copied = legacyCopy(text); } catch (ignored) { /* Report an explicit failure below. */ }
         finish(copied);
      }
      try {
         var clipboard = window.isSecureContext && window.navigator && window.navigator.clipboard;
         if (clipboard && typeof clipboard.writeText === "function") {
            Promise.resolve(clipboard.writeText(text)).then(function () { finish(true); }, fallback);
         } else {
            // HTTP has no Clipboard API: copy synchronously while the click still has user activation.
            fallback();
         }
      } catch (ignored) {
         fallback();
      }
   }
   if (/^#statement-[0-9]+$/.test(window.location.hash)) {
      var initial = document.getElementById(window.location.hash.substring(1));
      if (initial) showStatement(initial, false);
   }
   document.addEventListener("click", function (event) {
      var leaf = event.target.closest(".sql-leaf");
      if (leaf) {
         if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
         event.preventDefault();
         var card = document.getElementById(leaf.getAttribute("href").substring(1));
         if (!card) {
            feedback("The corresponding SQL statement is unavailable. Reload the diagnostic page.");
            return;
         }
         showStatement(card, true);
      }
      var treeAction = event.target.closest("[data-tree-action]");
      if (treeAction) {
         var expand = treeAction.getAttribute("data-tree-action") === "expand";
         treeAction.closest(".request").querySelectorAll(".method-context, .call-node").forEach(function (node) {
            node.open = expand;
         });
      }
      var sqlCopy = event.target.closest(".copy-sql");
      if (sqlCopy) {
         copyText(sqlCopy, sqlCopy.closest(".statement-card"), ".sql-text", ".sql-feedback", "SQL");
      }
      var treeCopy = event.target.closest(".copy-tree");
      if (treeCopy) {
         copyText(treeCopy, treeCopy.closest(".request"), ".call-tree-text", ".tree-feedback", "Call Tree");
      }
   });
}());
