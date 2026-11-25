// theme.js — runs before the page is drawn and applies the saved theme
// (Light, Dark or System) so there is no flash of the wrong colours.
// "System" simply leaves the attribute off and the CSS follows the device.
(function () {
  "use strict";
  try {
    var t = localStorage.getItem("rw-theme");
    if (t === "light" || t === "dark") document.documentElement.setAttribute("data-theme", t);
  } catch (e) { /* storage blocked: fall back to System */ }
})();
