# Chat

_Started 2026-04-18 09:11 UTC_

---

## User

I want a personal podcasting app that uses the free podcastindex.org. I should
  be able to search for a podcast, save it in a list (multiple list allowed), search result should show title, thumbnail and description of podcast. Allow search by title and person. Allow clicking of the search result to view podcast
  details and list of episodes. Allow saving of podcast episodes into device. Implement daily scheduled check (inaccurate / power saving scheduler), and show notification if there's any new podcast. No music, no live podcast is needed.
  No sound bites. Allow sign in by Google so the list can be backed up to a personal gdrive or app backup in playstore (please propose). Allow deletion of podcast. Allow setting of max size allowed to be used by the auto downloader.
  Allow on-demand downloads as well. Podcasting key should be configurable and injected during build time. The app should be use a combination of purple, white and neon pink for colors for its light mode. There should be a dark mode as well which should be more muted and used at night. Cheerful and modern is what we're looking for.

## Assistant

I'll design a high-fidelity interactive prototype for "Kofipod" — a personal podcasting app. Let me ask a few quick clarifying questions before diving in, since there's a lot of surface area here.

_[tool: questions_v2]_

## User

Questions answered:
- platform: Android (phone)
- scope: Onboarding / Google sign-in, Search (title + person), Podcast detail + episodes, Lists (library management), Downloads manager, Notifications (new episode), Settings (backup, auto-download size, dark mode), Daily-check scheduler explainer
- fidelity: Polished hi-fi, static screens only
- accent_balance: Purple-dominant, pink only for key CTAs/accents
- dark_mode_tone: Deep near-black with muted purple + soft pink glow
- typography: Decide for me
- backup_proposal: Google Drive appDataFolder (hidden, per-app, auto)
- lists_model: Folders (a podcast lives in one list)
- tweaks_focus: Decide for me
- notification_style: Decide for me
- scheduler_ui: Simple toggle + 'checks ~once a day' copy in Settings
- variations: One strong opinionated direction

## Assistant

Kofipod is delivered — 8 Android screens in a design canvas, light + dark, Tweaks panel for accent colors.

**Highlights**
- **Backup proposal**: Google Drive `appDataFolder` — hidden, per-app, no user-visible files, no quota cost. Settings row explains "Library, lists, playback positions. No audio files."
- **Scheduler**: Simple toggle in Settings ("Checks ~once a day when on Wi-Fi. Battery-aware") + a dedicated explainer screen with a last-7-runs chart.
- **Lists**: Folders model — podcast lives in one list. "Saved to Tech" confirms the folder in the detail CTA.
- **Build-time key**: Called out on a sticky note; no UI surface for the podcast index key.

**Caveats / next steps**
- No real podcast art — purple-striped placeholders throughout. Swap in real artwork when available.
- Interactions are static (hi-fi mockup, per your choice). Ready to turn any of these into clickable flows.

## User

A background task has completed. Here are the results:

## Assistant

_[tool: str_replace_edit]_

_[tool: snip]_

_[tool: done]_

Fixed — post-its now anchor to the correct container.

