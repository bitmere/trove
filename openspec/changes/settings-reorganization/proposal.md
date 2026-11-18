# Settings reorganization

Settings is one long scrolling page: every feature added its section to the
same screen (provider list, TorBox key, five more provider sign-ins,
default player, clutter files, clipboard offers, release preferences,
theme, backup, app updates). The page is long enough that finding one
setting means scrolling past all the others.

Split it into grouped sub-screens behind one root list: Providers, Library,
Playback, Appearance, Updates and Backup. The root screen lists the six
groups; tapping one opens its sub-screen with a back arrow, and the system
back button returns to the root list before leaving Settings. Every existing
setting stays reachable with the same behaviour, in the same place within
its group, using the same design tokens and visual language.

No setting is added, removed or reworded in behaviour; no dependency,
version, signing, workflow or release-process changes. The Settings
ViewModels and their state are untouched — only the composition moves.
Source-only work: the coordinator runs builds, focused tests, the full JVM
suite and lint after diff review. Navigation on a phone remains a device
check.
