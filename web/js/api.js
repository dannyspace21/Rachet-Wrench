// api.js — the only place the web app talks to the server.
//
// * Uses your login cookie automatically (same site only).
// * For any change (POST/PATCH/PUT/DELETE) it copies the CSRF cookie into
//   the X-CSRF-Token header — the server refuses changes without it, which
//   stops other websites from making changes on your behalf.
// * If the server says "not logged in" (401), it sends you to the login page.

/** Read one cookie by name (only the non-secret CSRF cookie is readable). */
function cookie(name) {
  const hit = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : "";
}

/** Error with the server's message, shown to the user in a toast. */
export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** Turn FastAPI's error body into one readable sentence. */
function messageFrom(body, status) {
  if (body && typeof body.detail === "string") return body.detail;
  if (body && Array.isArray(body.detail) && body.detail.length) {
    const d = body.detail[0];
    const field = Array.isArray(d.loc) ? d.loc[d.loc.length - 1] : "";
    return field ? `${field}: ${d.msg}` : d.msg;
  }
  return `Request failed (${status}).`;
}

/**
 * Call the API.  `body` may be a plain object (sent as JSON) or FormData
 * (for file uploads).  Returns the parsed JSON response.
 */
export async function api(path, { method = "GET", body, raw = false } = {}) {
  const headers = {};
  let payload;
  if (body instanceof FormData) {
    payload = body;
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  if (method !== "GET") headers["X-CSRF-Token"] = cookie("rw_csrf");
  const res = await fetch(path, { method, headers, body: payload, credentials: "same-origin" });
  if (res.status === 401 && !path.startsWith("/api/auth/login")) {
    const here = location.hash || "#/";
    if (!here.startsWith("#/login")) location.hash = "#/login?next=" + encodeURIComponent(here.slice(1));
    throw new ApiError(401, "Please log in.");
  }
  if (raw) return res;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok) throw new ApiError(res.status, messageFrom(data, res.status));
  return data;
}

// Small cache for things that rarely change during a visit.
let catalogPromise = null;
/** The shared catalog (types, spec fields, makes) — fetched once. */
export function getCatalog() {
  if (!catalogPromise) catalogPromise = api("/api/catalog");
  return catalogPromise;
}

let mePromise = null;
/** The logged-in user + settings (cached; call refreshMe() after changes). */
export function getMe() {
  if (!mePromise) mePromise = api("/api/auth/me").catch((e) => { mePromise = null; throw e; });
  return mePromise;
}
export function refreshMe() { mePromise = null; return getMe(); }
export function forgetMe() { mePromise = null; }
