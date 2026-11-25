// docs-init.js — starts Swagger UI on the API docs page.
// "Try it out" requests reuse your web login cookie; for changes we copy the
// CSRF cookie into the X-CSRF-Token header, exactly like the web app does.
(function () {
  "use strict";
  function readCookie(name) {
    const match = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
    return match ? decodeURIComponent(match.split("=")[1]) : "";
  }
  window.SwaggerUIBundle({
    url: "/api/openapi.json",
    dom_id: "#swagger-ui",
    validatorUrl: null, // never contact the public swagger.io validator
    deepLinking: true,
    requestInterceptor: function (req) {
      req.headers["X-CSRF-Token"] = readCookie("rw_csrf");
      req.credentials = "same-origin";
      return req;
    },
  });
})();
