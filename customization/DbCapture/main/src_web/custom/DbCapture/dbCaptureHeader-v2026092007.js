/**
 * Capture controls live in the Windchill shell. Server state owns permissions;
 * client sequencing prevents old polls and repeated clicks from contradicting it.
 */
(function () {
   "use strict";

   var ENDPOINT = "netmarkets/jsp/dbcapture/dbCaptureState.jsp";
   var state = {
      running: false, captureId: null, startedBy: null, startedAtMillis: 0,
      administrator: false, ownedByCurrentUser: false, canStart: false, canStop: false,
      csrfNonce: null
   };
   var known = false;
   var operation = null;
   var promptOpen = false;
   var generation = 0;
   var stateRequestPending = null;
   var statusError = "";
   var idleStateNotice = "";
   var captureWindowVisible = false;
   var notice = "";
   var lastMenuState = "";
   var pollingStarted = false;
   var bannerHeader = null;
   var bannerLayoutBusy = false;
   var bannerResizeObserver = null;
   var observedBannerElements = [];
   var fallbackTooltipTarget = null;
   var fallbackTooltipOriginal = null;
   var fallbackTooltipValue = null;

   function button(id) {
      return typeof Ext !== "undefined" && Ext.getCmp ? Ext.getCmp(id) : null;
   }

   function ensureBanner() {
      var header = document.getElementById("header");
      if (!header) { return null; }
      var banner = document.getElementById("dbCaptureGlobalBanner");
      if (banner) { return banner; }
      bannerHeader = header;
      banner = document.createElement("div");
      banner.id = "dbCaptureGlobalBanner";
      banner.setAttribute("role", "status");
      banner.setAttribute("aria-live", "polite");
      banner.tabIndex = 0;
      banner.style.display = "none";
      banner.dbcArt = document.createElement("span");
      banner.dbcArt.className = "dbcBannerArt";
      banner.appendChild(banner.dbcArt);
      function part(className, text) {
         var element = document.createElement("span");
         element.className = className;
         element.textContent = text;
         element.setAttribute("aria-hidden", "true");
         banner.dbcArt.appendChild(element);
         return element;
      }
      part("dbcBannerOverline", "T\u30fbH\u30fbE");
      banner.dbcHeadline = part("dbcBannerHeadline", "");
      part("dbcBannerTagline", "\uff83\uff9e\uff70\uff8b\uff9e\uff70\uff77\uff6c\uff8c\uff9f\uff81\uff6c\uff70");
      banner.dbcCompact = document.createElement("span");
      banner.dbcCompact.className = "dbcBannerCompact";
      banner.dbcCompact.textContent = "\ud83e\udd77";
      banner.dbcCompact.setAttribute("aria-hidden", "true");
      banner.appendChild(banner.dbcCompact);
      banner.dbcDetail = document.createElement("span");
      banner.dbcDetail.className = "dbcStatusLabel";
      banner.appendChild(banner.dbcDetail);
      header.appendChild(banner);
      return banner;
   }

   function fallbackTooltip(text) {
      if (fallbackTooltipTarget) {
         if (fallbackTooltipTarget.getAttribute("title") === fallbackTooltipValue) {
            if (fallbackTooltipOriginal === null) { fallbackTooltipTarget.removeAttribute("title"); }
            else { fallbackTooltipTarget.setAttribute("title", fallbackTooltipOriginal); }
         }
         fallbackTooltipTarget = null;
      }
      if (!text) { return; }
      var target = document.getElementById("quickLinksButton");
      if (!target) { return; }
      fallbackTooltipTarget = target;
      fallbackTooltipOriginal = target.getAttribute("title");
      fallbackTooltipValue = (fallbackTooltipOriginal ? fallbackTooltipOriginal + "\n\n" : "") + text;
      target.setAttribute("title", fallbackTooltipValue);
   }

   function headerObstacles(header, banner, bounds) {
      var elements = [];
      function add(element) {
         if (!element || element === header || element === banner || banner.contains(element)
               || elements.indexOf(element) >= 0) { return; }
         var rect = element.getBoundingClientRect();
         var style = window.getComputedStyle ? window.getComputedStyle(element) : null;
         if (!rect.width || !rect.height || rect.bottom <= bounds.top || rect.top >= bounds.bottom
               || (style && (style.display === "none" || style.visibility === "hidden"))) { return; }
         elements.push(element);
      }
      add(document.getElementById("logoNav"));
      add(document.getElementById("globalUser"));
      add(document.getElementById("quickLinksButton"));
      ["typeChooserTrigger", "globalSearch"].forEach(function (id) {
         var group = document.getElementById(id);
         add(group && (group.querySelector(".x-form-field-wrap") || group));
      });
      var controls = header.querySelectorAll("a, button, input, select, textarea, img, [role=button], .x-btn");
      for (var i = 0; i < controls.length; i++) { add(controls[i]); }
      // ActiveMS renders its H1 beside the header, not inside it.
      var headings = document.querySelectorAll("h1");
      for (var j = 0; j < headings.length; j++) { add(headings[j]); }
      return elements;
   }

   function widestHeaderGap(bounds, elements, padding) {
      var intervals = elements.map(function (element) {
         var rect = element.getBoundingClientRect();
         return {left: Math.max(padding, rect.left - bounds.left - padding),
            right: Math.min(bounds.width - padding, rect.right - bounds.left + padding)};
      }).filter(function (interval) { return interval.right > interval.left; });
      intervals.sort(function (a, b) { return a.left - b.left; });
      var best = {left: 0, right: 0}, cursor = padding;
      function consider(left, right) {
         if (right - left > best.right - best.left
               || (right - left === best.right - best.left
                  && Math.abs((left + right) / 2 - bounds.width / 2)
                     < Math.abs((best.left + best.right) / 2 - bounds.width / 2))) {
            best = {left: left, right: right};
         }
      }
      for (var i = 0; i < intervals.length; i++) {
         consider(cursor, intervals[i].left);
         cursor = Math.max(cursor, intervals[i].right);
      }
      consider(cursor, bounds.width - padding);
      return best;
   }

   function observeBannerLayout(elements) {
      if (!bannerResizeObserver && window.ResizeObserver) {
         bannerResizeObserver = new window.ResizeObserver(layoutBanner);
      }
      if (!bannerResizeObserver) { return; }
      for (var i = 0; i < observedBannerElements.length; i++) {
         if (elements.indexOf(observedBannerElements[i]) < 0) {
            bannerResizeObserver.unobserve(observedBannerElements[i]);
         }
      }
      for (var j = 0; j < elements.length; j++) {
         if (observedBannerElements.indexOf(elements[j]) < 0) { bannerResizeObserver.observe(elements[j]); }
      }
      observedBannerElements = elements;
   }

   function layoutBanner() {
      var banner = document.getElementById("dbCaptureGlobalBanner");
      var header = bannerHeader;
      if (!banner || !header || bannerLayoutBusy) { return; }
      bannerLayoutBusy = true;
      try {
         if (banner.style.display === "none") {
            fallbackTooltip(!known && statusError ? banner.title : "");
            banner.setAttribute("data-dbc-layout", "hidden");
            return;
         }
         var bounds = header.getBoundingClientRect();
         var obstacles = headerObstacles(header, banner, bounds);
         observeBannerLayout([header, banner.dbcArt].concat(obstacles));
         var gap = widestHeaderGap(bounds, obstacles, 6);
         var width = Math.min(360, gap.right - gap.left);
         var height = Math.min(36, Math.max(0, bounds.height - 6));
         var naturalWidth = banner.dbcArt.offsetWidth || 280;
         var naturalHeight = banner.dbcArt.offsetHeight || 32;
         var scale = Math.min(1, (width - 12) / naturalWidth, (height - 4) / naturalHeight);
         var compact = scale < 0.52;
         if (compact) {
            gap = widestHeaderGap(bounds, obstacles, 2);
            width = Math.min(28, gap.right - gap.left);
            height = Math.min(28, Math.max(0, bounds.height - 4));
         }
         var noSpace = width < 6 || height < 6;
         banner.className = banner.className.replace(/\s+dbcCaptureGlobalBanner(?:Compact|NoSpace)\b/g, "")
               + (noSpace ? " dbcCaptureGlobalBannerNoSpace" : compact ? " dbcCaptureGlobalBannerCompact" : "");
         banner.setAttribute("data-dbc-layout", noSpace ? "accessible" : compact ? "compact" : "full");
         if (noSpace) {
            // Never cover a control when a customized/narrow header has no usable gap.
            banner.style.width = "1px";
            banner.style.height = "1px";
            banner.style.left = "0px";
            banner.style.top = "0px";
            fallbackTooltip(banner.title);
            return;
         }
         fallbackTooltip("");
         var left = Math.max(gap.left, Math.min((bounds.width - width) / 2, gap.right - width));
         banner.style.width = width + "px";
         banner.style.height = height + "px";
         banner.style.left = left + "px";
         banner.style.top = (bounds.height - height) / 2 + "px";
         banner.dbcArt.style.transform = "translate(-50%, -50%) scale(" + Math.max(0, scale) + ")";
         banner.dbcCompact.style.transform = "translate(-50%, -50%) scale("
               + Math.min(1, (width - 4) / 20, (height - 4) / 20) + ")";
      } finally {
         bannerLayoutBusy = false;
      }
   }

   function renderBanner() {
      var banner = ensureBanner();
      if (!banner) { return; }
      var text = "";
      if (!known && statusError) {
         text = "DB Ninja state unavailable. " + statusError
               + " Capture activity cannot be confirmed; controls are disabled until status recovers.";
         if (state.running) { text += " Last confirmed running capture: " + state.captureId + "."; }
      } else if (operation === "start") {
         text = "Ninja Trick: starting database capture. Wait for the running confirmation before performing the operation.";
      } else if (operation === "stop") {
         text = "Ninja Stealth: collecting results for " + (state.captureId || "your capture")
               + ". Do not repeat Stop or perform additional business operations.";
      } else if (state.running && known) {
         var owner = state.startedBy || "another user";
         var started = state.startedAtMillis > 0
               ? " since " + new Date(state.startedAtMillis).toLocaleString() : "";
         text = state.ownedByCurrentUser
               ? "DB Ninja capture " + (state.captureId || "") + " is running under your user account"
                     + started + ". Perform only the intended operation, then choose Ninja Stealth in Quick Links."
               : "DB Ninja capture " + (state.captureId || "") + " is running, started by "
                     + owner + started + ". Wait for completion and do not perform business operations.";
         if (notice) { text += " " + notice; }
      }
      var headline = "\ud83e\udd77DB IS BEING H\u2694JACKED\uff01";
      if (banner.dbcHeadline.textContent !== headline) { banner.dbcHeadline.textContent = headline; }
      var accessibleText = text && captureWindowVisible ? "T\u30fbH\u30fbE\n" + headline
            + "\n\uff83\uff9e\uff70\uff8b\uff9e\uff70\uff77\uff6c\uff8c\uff9f\uff81\uff6c\uff70\n\n" + text : text;
      if (banner.dbcDetail.textContent !== accessibleText) { banner.dbcDetail.textContent = accessibleText; }
      banner.title = accessibleText;
      banner.setAttribute("aria-label", accessibleText);
      banner.className = text && captureWindowVisible ? "dbcCaptureGlobalBanner "
            + (state.ownedByCurrentUser
               ? "dbcCaptureGlobalBannerOwner" : "dbcCaptureGlobalBannerOther") : "";
      banner.style.display = text && captureWindowVisible ? "block" : "none";
      layoutBanner();
   }

   function render() {
      var menuState = [known, state.running, state.captureId, state.startedBy,
            state.startedAtMillis, state.administrator, state.canStart, state.canStop, operation].join("|");
      if (menuState !== lastMenuState) {
         lastMenuState = menuState;
         var quickLinks = button("quickLinksButton");
         if (quickLinks && quickLinks.menu) {
            quickLinks.menu.loaded = false;
            if (quickLinks.menu.isVisible && quickLinks.menu.isVisible()) { quickLinks.menu.hide(); }
         }
      }
      renderBanner();
   }

   function notify(message, ok) {
      if (!message) { return; }
      if (ok) {
         notice = message;
         renderBanner();
         return;
      }
      var text = Ext.util.Format.htmlEncode(String(message));
      if (typeof PTC !== "undefined" && PTC.util && PTC.util.showMessage) {
         PTC.util.showMessage(text);
      } else if (Ext.MessageBox) {
         Ext.MessageBox.alert("DB Ninja - failed", text);
      }
   }

   function acceptState(data) {
      if (!data || typeof data.ok !== "boolean" || typeof data.running !== "boolean"
            || typeof data.administrator !== "boolean" || typeof data.canStart !== "boolean"
            || typeof data.canStop !== "boolean" || data.stateKnown === false
            || (!data.ok && data.stateKnown !== true)) { return false; }
      if (state.running !== data.running || state.captureId !== data.captureId) { notice = ""; }
      state.running = data.running;
      captureWindowVisible = data.running;
      state.captureId = data.captureId;
      state.startedBy = data.startedBy;
      var started = Number(data.startedAtMillis);
      state.startedAtMillis = isFinite(started) && started > 0 ? started : 0;
      state.administrator = data.administrator;
      state.ownedByCurrentUser = data.ownedByCurrentUser === true;
      state.canStart = data.canStart;
      state.canStop = data.canStop;
      state.csrfNonce = data.administrator && typeof data.csrfNonce === "string"
         ? data.csrfNonce : null;
      known = true;
      statusError = "";
      idleStateNotice = "";
      return true;
   }

   function call(params, announce) {
      params = params || {};
      var stateOnly = !params.op;
      if (!stateOnly && state.csrfNonce) { params.CSRF_NONCE = state.csrfNonce; }
      if (stateOnly && (operation || (stateRequestPending
            && stateRequestPending.generation === generation))) { return; }
      if (!stateOnly) { generation++; }
      var requestGeneration = generation;
      var marker = {generation: generation};
      if (stateOnly) { stateRequestPending = marker; }

      function received() {
         if (stateOnly && stateRequestPending === marker) { stateRequestPending = null; }
         if (requestGeneration !== generation) { return false; }
         if (!stateOnly) { operation = null; notice = ""; }
         return true;
      }
      function unavailable(message) {
         known = false;
         statusError = message;
         render();
         if (announce) { notify(message, false); }
         else if (!captureWindowVisible && idleStateNotice !== message) {
            idleStateNotice = message;
            notify("DB Ninja state unavailable. " + message
                  + " Capture activity cannot be confirmed; controls are disabled until status recovers.", false);
         }
         if (!stateOnly) { call({}, false); }
      }
      Ext.Ajax.request({
         url: ENDPOINT, method: "POST",
         timeout: params.op === "stop" ? 180000 : 30000,
         params: params,
         success: function (response) {
            if (!received()) { return; }
            var data;
            try {
               data = Ext.decode(response.responseText);
            } catch (e) {
               unavailable("DB Capture returned an unreadable response.");
               return;
            }
            if (!acceptState(data)) {
               unavailable(data && data.message ? data.message
                     : "DB Capture returned an unreadable or incomplete state response.");
               return;
            }
            render();
            if (params.op === "stop" && data.ok === true) {
               if (!/^CAP-[0-9]+$/.test(data.completedCaptureId || "")
                     || typeof data.resultsUrl !== "string" || !data.resultsUrl) {
                  notify("The capture finished, but no result link was returned."
                        + " Open DB Ninja and press Search.", false);
                  return;
               }
               if (Ext.MessageBox && Ext.MessageBox.hide) { Ext.MessageBox.hide(); }
               try {
                  (window.top || window).location.assign(data.resultsUrl);
               } catch (e) {
                  notify("The capture finished, but its results page could not be opened: " + e, false);
               }
               return;
            }
            if (announce) { notify(data.message, data.ok); }
         },
         failure: function () {
            if (!received()) { return; }
            unavailable("DB Capture could not be reached."
                  + (!stateOnly ? " The operation outcome is not confirmed; checking its state before retrying." : ""));
         }
      });
   }

   function start() {
      if (operation || promptOpen) { return; }
      if (!known || !state.canStart) {
         notify("Ninja Trick is currently unavailable. Check the capture status and ownership.", false);
         return;
      }
      if (!window.confirm("Ninja Trick: start recording database changes?\n\nDatabase changes across this Windchill database will be monitored."
            + "\nOther users should pause unrelated business operations. Continue?")) { return; }
      promptOpen = true;
      Ext.MessageBox.prompt("Ninja Trick",
            "What are you investigating? (optional, maximum 400 characters)",
            function (choice, text) {
               promptOpen = false;
               if (choice !== "ok") { return; }
               if (!known || !state.canStart || operation) {
                  notify("Capture state changed while the prompt was open. Ninja Trick was not submitted.", false);
                  return;
               }
               if ((text || "").length > 400) {
                  notify("Description must be 400 characters or fewer. No capture was started.", false);
                  return;
               }
               operation = "start";
               captureWindowVisible = true;
               notice = "";
               render();
               call({op: "start", label: text || ""}, true);
            });
      if (Ext.MessageBox.getDialog) {
         var input = Ext.MessageBox.getDialog().getEl().dom.querySelector('input[type="text"]');
         if (input) {
            input.maxLength = 400;
            input.setAttribute("aria-label", "Capture description (maximum 400 characters)");
         }
      }
   }

   function stop() {
      if (operation) { return; }
      if (!known || !state.canStop) {
         notify("Ninja Stealth is currently unavailable. Only the starting user can stop a running capture.", false);
         return;
      }
      if (!window.confirm("Ninja Stealth: stop recording and collect results?\n\nThis ends your running capture and collects database changes."
            + "\nContinue only after the intended operation is complete.")) { return; }
      operation = "stop";
      notice = "";
      render();
      call({op: "stop"}, true);
   }

   window.DbCaptureHeader = {
      getState: function () {
         return {known: known, running: state.running, captureId: state.captureId,
            canStart: known && !operation && state.canStart,
            canStop: known && !operation && state.canStop};
      },
      startFromAction: function () { start(); return false; },
      stopFromAction: function () { stop(); return false; },
      initialize: initialize
   };

   function initialize() {
      if (!Ext.get("pageHeaderActions")) { return; }
      render();
      call({}, false);
      if (!pollingStarted) {
         pollingStarted = true;
         setInterval(function () { call({}, false); }, 10000);
         if (document.addEventListener) {
            document.addEventListener("visibilitychange", function () {
               if (!document.hidden) { call({}, false); }
            }, false);
         }
         if (window.addEventListener) {
            window.addEventListener("resize", layoutBanner, false);
            window.addEventListener("focus", function () { call({}, false); }, false);
         }
      }
   }
   PTC.navigation.on("afterHeaderRender", initialize);
})();
