# Affiliate links for Smart Shopping (administrator guide)

Smart Shopping's retailer buttons (Amazon, Walmart, Advance Auto Parts) can carry
affiliate links. **Everything is switched off.** Ratchet Wrench works fully without
them, and no affiliate identifier is included anywhere. This page explains how
an administrator can turn on a program they have been **approved** for.

## How it works

* **One file:** `catalog/affiliates.json`. Nothing else in the code holds
  affiliate settings.
* **Per retailer, per platform.** Each retailer has `enabled` and a list of
  `platforms` where links may be used:
  * `web`: the web app served by a home server or Docker (opened in a browser),
  * `desktop`: the downloadable desktop app (Windows/macOS/Linux),
  * `android`: the Android app.
  A retailer can be on for `web` and off everywhere else, for example.
* **Two link methods**, matching how the programs work:
  * `"method": "param"`: adds parameters to the ordinary search address, e.g.
    Amazon's `tag`. Fill in `"params": {"tag": "<your exact tracking ID>"}`.
  * `"method": "wrap"`: puts the ordinary search address inside the network's
    deep link, e.g. Impact's. Paste the link template the network gave you,
    replacing its destination with `{url}`:
    `"wrap": "https://<network host>/c/<id>/<ad>/<campaign>?u={url}"`.
* **The search never changes.** Affiliate settings are applied after the
  search text is final. The same product search is opened with or without
  them, and a test checks this on web and Android. Suggestions (what to buy,
  which part number) come only from the vehicle's specs and parts, never
  from commissions.
* **Disclosure.** Each retailer has a `disclosure` sentence. When an affiliate
  link is shown, the shopping dialog marks those buttons "affiliate" and shows
  the disclosure. Settings → Smart Shopping shows it too.
* **Users can opt out.** When a program is active, Settings → Smart Shopping has a
  *Use affiliate links* switch, per device. With it off, the links are the
  ordinary search links.
* **Safety checks.** `python3 catalog/build_catalog.py` refuses invalid
  settings:
  * enabled without platforms or without a disclosure,
  * empty identifiers or placeholders (`YOUR…`, `XXXX`, `example`, …),
  * a non-https wrap link, or one without `{url}`,
  * an unknown retailer.
* **No accounts, servers or cloud** are added. Links are plain URLs opened in
  the browser or the retailer's app.

## Steps to turn on a program

1. **Get approved** by the program, and keep the approval email.
2. **Read the program's CURRENT operating agreement** (they change). Confirm
   which of `web`, `desktop` and `android` it allows (see below).
3. Edit `catalog/affiliates.json` for that retailer:
   * `"enabled": true`,
   * `"platforms"`: ONLY the ones the terms allow,
   * the exact identifiers or link template from the program (never made up),
   * a `"disclosure"` that meets the program's wording rules.
4. Run `python3 catalog/build_catalog.py`. Fix anything it refuses.
5. Commit and push. The next release includes it. Check a link on each
   enabled platform using the program's link-checking tool.

To turn a program off, set `"enabled": false` (or remove the platform) and release again.

## What the programs said when this was set up (November 2026)

Re-check these. Terms change, and approval for a **website** does not
automatically allow links in **downloadable apps**.

### Amazon Associates (method: `param`, `tag=`)

* Amazon's Program Policies forbid Special Links in **any client-side
  software**, including desktop apps, browser add-ons, toolbars and
  installable apps, on any device, **other than "Approved Mobile
  Applications"**.
* An Approved Mobile Application must follow Amazon's **Mobile Application
  Policy**: it must be listed in an app store (Google Play, Apple or Amazon) and
  approved by Amazon. Commissions in apps require links served through
  Amazon's product API. Breaking the mobile policy terminates the agreement.
* **What this means for Ratchet Wrench today:**
  * `desktop`: **not allowed**.
  * `android`: **not allowed** while the app is distributed as an APK from
    GitHub (it isn't an app-store-listed, Amazon-approved app, and links
    aren't served by Amazon's API).
  * `web`: only if Amazon has approved the site that serves it. A copy of
    Ratchet Wrench running on someone else's home server is not your approved
    site.
  * Keep Amazon **off** unless all of that changes.
* Sources: [Associates Program Policies](https://affiliate-program.amazon.com/help/operating/policies),
  [Participation Requirements](https://affiliate-program.amazon.com/help/operating/participation/).

### Walmart Affiliates, through Impact (method: `wrap`)

* Walmart's operating agreement says links must be properly formatted
  **Qualifying Links** to Walmart's site. In practice these are the deep links
  Impact generates, so use `wrap` with the template Impact gives you.
* It requires a **clear and conspicuous disclosure** beside the links.
* It forbids software that intercepts or redirects traffic or referral fees,
  and it has restrictions on paid search.
* Confirm in Impact whether links inside a downloadable app are allowed before
  listing `desktop` or `android`.
* Sources: [Walmart Operating Agreement](https://affiliates.walmart.com/terms),
  [June 2024 copy (PDF)](https://marketplace.walmart.com/wp-content/uploads/2024/11/Impact-Terms.pdf).

### Advance Auto Parts (method: `wrap`)

* The program runs mainly through **Impact** (some listings also mention other
  networks). Published commission rates conflict (about 4% to 10%), so check
  the live terms in the network after approval.
* Use the Impact deep-link template as `wrap`. Confirm app placement is allowed
  before listing `desktop` or `android`.
* Sources: [Advance Auto Parts affiliate page](https://shop.advanceautoparts.com/o/affiliates).

### FTC

US law (FTC Endorsement Guides) requires clear disclosure of affiliate
relationships. The `disclosure` sentence is shown next to affiliate links. Keep
it plain, for example "As an Amazon Associate I earn from qualifying purchases."

## Adding another retailer or program

1. Add the retailer to `RETAILERS` in `catalog/build_catalog.py` (key, name,
   icon, colour, `search_url` with `{q}`, `enabled`).
2. Add a block for it in `catalog/affiliates.json`: program name,
   `"enabled": false`, method, params or wrap, disclosure.
3. Rebuild the catalog. It appears in the web app, the desktop app and Android
   (Settings → Smart Shopping lists it), and the shared tests cover it.
