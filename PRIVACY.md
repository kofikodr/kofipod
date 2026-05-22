# Kofipod Privacy Policy

_Last updated: 2026-05-23_

Kofipod is a personal podcast app. This page describes what data the app
collects, what it does **not** collect, and how you can turn it off.

## Short version

- **No accounts.** There is no sign-in. The app never asks for your
  email, name, phone number, or any other identifier.
- **No tracking IDs.** The app does not generate or store a per-install
  identifier and does not send one to any server.
- **No data sales, ever.** There is no business model that involves
  selling, renting, or sharing user data with advertisers, brokers, or
  any third party. There is no plan to introduce one.
- **Two opt-in diagnostic channels** — described below — both of which
  can be turned off in **Settings → Privacy & Diagnostics**.
- **Forks and F-Droid builds are inert.** Without the maintainer's
  build keys, neither diagnostic SDK is initialized, no network calls
  are made, and the in-app disclosure sheet does not appear.

## What the app does on the network

In normal use the app talks to:

- **Podcast Index** — to search for shows and fetch episode metadata.
- **Apple iTunes Search API** (`itunes.apple.com/search`) — the second
  podcast catalogue Kofipod queries alongside Podcast Index, so shows
  one index has and the other doesn't are still findable. Your typed
  search term and your selected **search storefront** (a country code
  like `US` / `GB` / `DE` chosen in Settings → Search) are sent to
  Apple in the URL. No identifier, no account, no cookies — the request
  is anonymous from Apple's side. Apple's privacy policy applies:
  <https://www.apple.com/legal/privacy/>. You can avoid this endpoint
  entirely by clearing your search query — no requests are made until
  you type.
- **The podcast publisher's own servers** — to download or stream the
  audio file you asked for, and to fetch artwork.
- **Google Generative Language API** — only if you have configured
  your own Gemini API key for AI summary / Discuss features. Your key
  stays on the device, encrypted. The maintainer never sees it.
- **Google's Auto Backup service** — encrypted device backups of your
  library are uploaded to your own Google account on Google's schedule.
  The maintainer cannot access them.

None of these requests carry an identifier the maintainer can use to
recognise you across sessions or devices.

## Diagnostic channels (opt-in, toggleable)

Both channels are off until you acknowledge a one-time disclosure on
first launch. Either can be toggled independently afterwards.

### Crash reports

When enabled, Kofipod sends details about crashes so the developer can
fix them. Sent over HTTPS to a self-hosted GlitchTip instance
(GlitchTip is a Sentry-protocol-compatible, MIT-licensed crash logger).

| Field | Example | Notes |
|---|---|---|
| Stack trace | `NullPointerException at app.kofipod...:123` | R8 minification is currently disabled, so no obfuscation is applied |
| Exception class & message | `IOException: Failed to fetch ...` | URLs scrubbed of query strings |
| OS version | `Android 14` | |
| Device model | `Pixel 7` | Not unique |
| App version | `1.4.2` | |
| Locale | `en-US` | |
| Breadcrumbs | navigation events | URLs to Gemini, Google APIs, Podcast Index, and Apple iTunes dropped; `query`-category breadcrumbs (SQL) dropped |
| Release tag | `1.4.2` | |

**Explicitly NOT sent:** IP address (server-side dropped), user ID,
email, screen contents, view hierarchy, breadcrumbs containing URLs to
Gemini / Podcast Index / Apple iTunes / Google APIs.

### Anonymous usage data

When enabled, Kofipod sends counts of how often features are used so
the developer can prioritize work. Sent over HTTPS to **Aptabase**
(`A-EU-…` app key — **hosted in the EU**).

Events emitted (v1):

- `app_opened`
- `search_performed` with `source` ∈ {`typed`, `category`}
- `episode_downloaded`
- `episode_played`
- `ai_summary_generated` with `path` ∈ {`transcript`, `audio`}
- `ai_discuss_message_sent` with `path` ∈ {`transcript`, `audio`}

Per-event metadata: app version, OS version, locale.

**No client identifier is ever sent.** Aptabase's server hashes
`SHA(your IP + user-agent + a daily-rotated salt)` only to count
distinct users in aggregate. Because the salt rotates every 24 hours,
the same device counts as a different anonymous user tomorrow. The raw
IP is not stored.

## Toggling and revoking

**Settings → Privacy & Diagnostics** has two switches, one per
channel. Turning a switch off stops all further sends from that
moment. It does not retroactively delete data already sent.

To request deletion of data already sent, email the maintainer
(see GitHub profile) — but note there is no per-user identifier on
file, so deletion is whole-collection only.

## Data retention

- Crash reports: 30 days (server-side configuration).
- Usage events: 12 months (Aptabase default).

## Data sharing and sales

The maintainer does **not** sell, rent, or share any of the above data
with advertisers, data brokers, or any other third party. There is no
plan to. The two diagnostic channels are operational tools for fixing
bugs and prioritising features — nothing more.

## Hosting

- Crashes: GlitchTip self-hosted on Railway.
  Source: <https://gitlab.com/glitchtip/glitchtip-frontend>
- Usage: Aptabase EU cloud (<https://aptabase.com>).
  SDK source (AGPL-3): <https://github.com/aptabase/aptabase-kotlin>

## Children

Kofipod is not directed at children under 13 and does not knowingly
collect any data from children. There is no account system to register
in the first place.

## Changes to this policy

Material changes will be reflected by bumping the **Last updated**
date at the top of this file. Because the app links to this file on
GitHub, you are always reading the current version.

## License of this app

GPL-3.0-or-later. See [`LICENSE`](LICENSE).
