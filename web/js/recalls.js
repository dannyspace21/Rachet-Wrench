// recalls.js — what to show for each of a vehicle's recalls.
//
// NHTSA lists recalls for a year/make/model; it can't say (publicly) whether
// one applies to, or was done on, a particular car.  So the status comes from
// what the owner knows:
//   * "done"            the owner marked it done, OR a recall record in the
//                       history mentions its campaign number (e.g. imported
//                       from Carfax: "Recall 20V012 performed");
//   * "not_applicable"  the owner checked and it doesn't apply to this car;
//   * "open"            otherwise.  "isNew" = open and reported in the last
//                       180 days.
// Identical rules on Android (Recalls in Logic.kt); both are checked against
// catalog/recalls_tests.json.

const norm = (s) => String(s || "").toUpperCase().replace(/[^0-9A-Z]/g, "");

/** The carmaker's own number for the recall, from NHTSA's text ("Kia's number for this recall is SC158."). */
export function makerNumber(recall) {
  const m = /number for this recall is ([A-Z0-9][A-Z0-9-]{2,15})/i.exec(`${recall.remedy || ""} ${recall.summary || ""}`);
  return m ? norm(m[1]) : null;
}

/** The history record that shows this recall was done (or null). */
export function doneInHistory(recall, entries) {
  const c = norm(recall.campaign);
  if (c.length < 4) return null;
  // "20V012000" is often written "20V-012" or "20V012"; dealers and Carfax
  // often use the carmaker's own number instead (e.g. SC158).
  const ids = [c.length === 9 && c.endsWith("000") ? c.slice(0, 6) : c, makerNumber(recall)].filter((x) => x && x.length >= 4);
  return entries.find((e) => !e.deleted_at && e.type_key === "recall"
    && ids.some((id) => norm(`${e.description} ${e.notes} ${e.custom_type}`).includes(id))) || null;
}

/** {status, entry, isNew} for one recall. */
export function recallState(recall, entries, today) {
  if (recall.status === "done") return { status: "done", entry: entries.find((e) => e.id === recall.entry_id) || null, isNew: false };
  if (recall.status === "not_applicable") return { status: "not_applicable", entry: null, isNew: false };
  const e = doneInHistory(recall, entries);
  if (e) return { status: "done", entry: e, isNew: false };
  const days = recall.report_date ? (Date.parse(today) - Date.parse(recall.report_date)) / 86400000 : Infinity;
  return { status: "open", entry: null, isNew: days <= 180 };
}

/** Open recalls first (newest first), then done / doesn't apply. */
export function sortRecalls(recalls, entries, today) {
  const rank = { open: 0, done: 1, not_applicable: 2 };
  return recalls.filter((r) => !r.deleted_at).map((r) => ({ r, s: recallState(r, entries, today) }))
    .sort((a, b) => rank[a.s.status] - rank[b.s.status] || String(b.r.report_date || "").localeCompare(String(a.r.report_date || "")));
}
