# Trove — design system

Recorded from the existing code
(`app/src/main/java/app/trove/debrid/ui/theme/Theme.kt`,
`docs/SKILLS.md`, `AGENTS.md`). Nothing here is new: this file describes
the system as it already is, so later work follows it.

## Tokens

- Colours (light): Paper `#F7FAF6` background/surface, Ink `#171717`
  text, Hairline `#E7E7E7`, Jade `#007D60` primary, Error `#BA1A1A`,
  Chip `#F2F2F2`. Dark: background/surface `#101511`, text `#E0E5DF`,
  primary `#91D5BB`. OLED mode: pure-black surfaces.
- Accents: Jade (default), Ocean, Ember, Rose. Modes: System, Light,
  Dark, OLED.
- Type: display + headline in Space Grotesk (Bold/SemiBold); body +
  labels in Inter (Regular/Medium/SemiBold/Bold). DisplayLarge 57/64
  down to labelSmall 11/16.
- Shapes: extraSmall 8dp, small 12dp, medium 20dp, large 24dp,
  extraLarge 32dp — expressive rounded geometry.
- Every Material role is set explicitly: sheets and dialogs must never
  inherit purple defaults.

## Components

- One primary destination, Downloads, with Torrents, Web and Usenet
  subtabs; search at the top of the list.
- Floating Add + Search controls at bottom right — the one liquid-glass
  exception. No bottom navigation.
- Settings behind the avatar, six groups: Providers, Library, Playback,
  Appearance, Updates, Backup.

## Rules

- Decide tokens first. Work inside the tokens above. Do not invent a new
  palette or typeface as part of a refinement.
- The no-ai-slop-design skill governs the banned list: no purple
  gradients, no glassmorphism outside the floating action cluster, no
  bento grids, no Inter-only type, no eyebrow labels. Run the swap test
  before shipping.
- Run the Impeccable review passes (critique, layout, typeset, simplify,
  clarify, polish, harden, audit) on UI changes, and run the detector
  before calling UI work done.
- Kotlin files are not in the detector's built-in extension list. The
  project config adds `.kt` with the text engine so the hook covers them.

## Skill resolution order

Project-local skills first, then user-level skills, then the ai-config
repo index as reference only. Never use a stale skill: if a skill is old,
leave it out and say so.
