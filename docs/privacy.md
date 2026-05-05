# Kofipod Privacy

Kofipod ships with two opt-in diagnostic channels. Both default ON in
non-F-Droid builds but no data is sent until you've acknowledged the
first-launch disclosure once. F-Droid builds are inert: the build keys
are empty so neither subsystem is ever initialized.

## Crash reports

When enabled, Kofipod sends details about crashes so the developer can
fix them. Sent over HTTPS to a self-hosted GlitchTip instance.

| Field | Example | Notes |
|---|---|---|
| Stack trace | `NullPointerException at app.kofipod...:123` | Human-readable — R8 minification is currently disabled, so no obfuscation is applied |
| Exception class & message | `IOException: Failed to fetch ...` | URLs scrubbed of query strings |
| OS version | `Android 14` | |
| Device model | `Pixel 7` | Not unique |
| App version | `1.4.2` | |
| Locale | `en-US` | |
| Breadcrumbs | navigation events | URLs to Gemini, Google APIs, and Podcast Index dropped; `query`-category breadcrumbs (SQL) dropped |
| Release tag | `1.4.2` | |

**Explicitly NOT sent:** IP address, user ID, screen contents, view
hierarchy, breadcrumbs containing URLs to Gemini / Podcast Index /
Google APIs.

## Anonymous usage data

When enabled, Kofipod sends counts of how often features are used so
the developer can prioritize work. Sent over HTTPS to Aptabase.

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
distinct users. Because the salt rotates every 24 hours, the same
device is a different ID tomorrow. The raw IP is not stored.

## Toggling

**Settings → Privacy & Diagnostics** — two switches, one for each
channel. Turning a switch off stops further sends. It does not delete
data already sent.

## Data retention

- Crash reports: 30 days (server-side configuration).
- Usage events: 12 months (Aptabase default).

## Hosting

- Crashes: GlitchTip self-hosted on Railway. Source:
  https://gitlab.com/glitchtip/glitchtip-frontend
- Usage: Aptabase cloud (https://aptabase.com). SDK source (AGPL-3):
  https://github.com/aptabase/aptabase-kotlin

## License of this app

GPL-3.0-or-later. See `LICENSE`.
