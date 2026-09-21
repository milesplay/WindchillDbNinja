(function () {
   "use strict";

   function plainText(value) {
      if (value === null || value === undefined) { return ""; }
      var template = document.createElement("template");
      template.innerHTML = String(value);
      function read(node) {
         if (node.nodeType === 3) { return node.nodeValue; }
         if (node.nodeName === "BR") { return "\n"; }
         if (/^(SCRIPT|STYLE)$/.test(node.nodeName)) { return ""; }
         if (node.nodeName === "TEXTAREA") { return node.textContent; }
         var text = "";
         for (var i = 0; i < node.childNodes.length; i++) { text += read(node.childNodes[i]); }
         return text;
      }
      return read(template.content).replace(/\u00a0/g, " ").trim();
   }

   function csvCell(value) {
      var text = value === null || value === undefined ? "" : String(value);
      // Quoting alone does not prevent a spreadsheet from executing a formula.
      if (/^[\s\u0000-\u001f]*[=+\-@]/.test(text) || /^[\t\r\n]/.test(text)) {
         text = "'" + text;
      }
      return '"' + text.replace(/"/g, '""') + '"';
   }

   function serialize(headers, rows) {
      return "\ufeff" + [headers].concat(rows).map(function (row) {
         if (row.length !== headers.length) { throw new Error("CSV row and column counts differ."); }
         return row.map(csvCell).join(",");
      }).join("\r\n") + "\r\n";
   }

   function filename(descriptions, captureIds) {
      if (!descriptions || !captureIds.length) { throw new Error("Capture descriptions are unavailable."); }
      var labels = captureIds.map(function (id) {
         if (!Object.prototype.hasOwnProperty.call(descriptions, id)
               || (descriptions[id] !== null && typeof descriptions[id] !== "string")) {
            throw new Error("Description metadata is missing for " + id + ".");
         }
         return descriptions[id] && descriptions[id].trim() ? descriptions[id].trim() : id;
      });
      var name = labels.join("__").replace(/[<>:"/\\|?*\u0000-\u001f\u007f]/g, "_")
            .replace(/^[. ]+|[. ]+$/g, "");
      if (!name) { name = captureIds.join("__"); }
      if (/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:[. ]|$)/i.test(name)) { name = "_" + name; }
      var bounded = "", bytes = 0;
      // Prototype replaces Array.from in Windchill, so iterate Unicode code points explicitly.
      for (var offset = 0; offset < name.length;) {
         var code = name.codePointAt(offset);
         var character = String.fromCodePoint(code);
         offset += character.length;
         if (code >= 0xd800 && code <= 0xdfff) { character = "_"; code = 95; }
         var length = code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
         if (bytes + length > 176) { break; }
         bounded += character;
         bytes += length;
      }
      return bounded.replace(/[. ]+$/g, "") + ".csv";
   }

   function snapshotGrid(grid) {
      if (!grid || !grid.getStore || !grid.getColumnModel) {
         throw new Error("Database Changes is not ready. Run Search first.");
      }
      var store = grid.getStore();
      if (store.loading || store.load_complete === false || (store.isLoading && store.isLoading())) {
         throw new Error("Wait for Database Changes to finish loading.");
      }
      // getRange follows the visible local filter/sort/page, unlike allData or snapshot.
      var records = store.getRange();
      if (!records.length) { throw new Error("No Database Changes rows are currently displayed."); }
      var model = grid.getColumnModel();
      var columns = [], headers = [];
      for (var c = 0; c < model.getColumnCount(); c++) {
         var field = model.getDataIndex(c);
         if (model.isHidden(c) || !field || /^(nmActions|checker)$/.test(field)) { continue; }
         var heading = plainText(model.getColumnHeader(c));
         if (!heading) { continue; }
         columns.push({index: c, field: field});
         headers.push(heading);
      }
      if (!columns.length) { throw new Error("No data columns are currently displayed."); }
      var captureIds = [];
      var rows = records.map(function (record, rowIndex) {
         var captureId = plainText(record.get("captureId"));
         if (!/^CAP-[0-9]{1,36}$/.test(captureId)) {
            throw new Error("A displayed row has an invalid Capture ID.");
         }
         if (captureIds.indexOf(captureId) < 0) { captureIds.push(captureId); }
         return columns.map(function (column) {
            var value = record.get(column.field);
            var renderer = model.getRenderer(column.index);
            if (renderer) {
               var config = model.config && model.config[column.index];
               value = renderer.call(config && config.scope ? config.scope : grid,
                     value, {}, record, rowIndex, column.index, store);
            }
            return plainText(value);
         });
      });
      if (captureIds.length > 500) {
         throw new Error("Select 500 or fewer captures before exporting Database Changes.");
      }
      return {headers: headers, rows: rows, captureIds: captureIds};
   }

   function download(snapshot, descriptions) {
      var name = filename(descriptions, snapshot.captureIds);
      var blob = new Blob([serialize(snapshot.headers, snapshot.rows)], {type: "text/csv;charset=utf-8"});
      var url = URL.createObjectURL(blob);
      var link = document.createElement("a");
      link.href = url;
      link.download = name;
      link.style.display = "none";
      document.body.appendChild(link);
      try {
         link.click();
      } catch (error) {
         URL.revokeObjectURL(url);
         throw error;
      } finally {
         link.remove();
      }
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
      return name;
   }

   function exportFromAction(event, target, table) {
      if (typeof window.dbcDownloadCsv !== "function") {
         window.alert("CSV export is unavailable. Open DB Ninja and wait for the table to load.");
         return false;
      }
      return window.dbcDownloadCsv(event, target, table);
   }

   window.DbCaptureCsv = {plainText: plainText, csvCell: csvCell, serialize: serialize,
      filename: filename, snapshotGrid: snapshotGrid, download: download, exportFromAction: exportFromAction};
})();
