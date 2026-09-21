(function () {
   "use strict";

   var icons = {
      startDbCapture: "netmarkets/images/dbcapture/dbNinjaTrick-v20260921.png",
      stopDbCapture: "netmarkets/images/dbcapture/dbNinjaStealth-v20260921.png"
   };
   var registered = false;
   var reported = false;

   function problem(message) {
      if (!reported && window.console) {
         console.error("DB Ninja action icons: " + message);
         reported = true;
      }
   }

   function decorate(menu) {
      var button = window.Ext && Ext.getCmp ? Ext.getCmp("quickLinksButton") : null;
      if (!menu || menu.id !== "quickLinksMenu" || !button || button.menu !== menu) { return; }
      if (!menu.items || typeof menu.items.each !== "function") {
         problem("Quick Links items are unavailable.");
         return;
      }
      menu.items.each(function (item) {
         if (!item || !Object.prototype.hasOwnProperty.call(icons, item.actionName)) { return; }
         var image = icons[item.actionName];
         item.icon = image;
         if (item.rendered) {
            if (!item.iconEl || typeof item.iconEl.set !== "function") {
               problem("The Ninja menu icon element is unavailable.");
               return;
            }
            item.iconEl.set({src: image});
         }
      });
   }

   function initialize() {
      if (!window.PTC || !PTC.menu || typeof PTC.menu.on !== "function") {
         problem("The Windchill dynamic-menu event API is unavailable.");
         return;
      }
      if (!registered) {
         PTC.menu.on("dynamicMenuLoad", decorate);
         PTC.menu.on("dynamicMenuShow", function (button, menu) { decorate(menu); });
         registered = true;
      }
      var button = window.Ext && Ext.getCmp ? Ext.getCmp("quickLinksButton") : null;
      if (button) { decorate(button.menu); }
   }

   window.DbNinjaActionIcons = {initialize: initialize};
})();
