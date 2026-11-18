# Default external player

Play currently opens the Android chooser for every file. Add a Default player setting that defaults to Ask every time and lists installed video handlers. Store only package/activity identifiers in the existing ordinary trove_accounts preferences through DebridRepository. AppContainer wires the repository and a thin PackageManager discovery adapter.

Play continues to resolve the selected kind and real file ID through requestdl and open an external ACTION_VIEW with the existing concrete filename-derived MIME. A saved, available component opens directly. Missing/stale components or launch failures fall back to the chooser without erasing the choice. Download details offer Play with… for the first file, and each file offers the same action in its overflow menu. This override affects one playback only.

Discovery always uses https://example.invalid/sample.mp4, never the requestdl bearer URL. Preferences and discovery execute off Main. Pure Kotlin component selection lives in Android-free core; the Android query and launch remain thin adapters. No new dependencies, version changes, signing/workflow changes, in-app player, or streaming API calls.

Design tokens, chosen before implementation: retain Paper #F7FAF6, Ink #171717, Jade #007D60, Hairline #E7E7E7 and existing dark counterparts. Retain Space Grotesk headings and Inter body, TroveSpace rhythm, existing Material shapes and surfaces. Use one settings row and a bounded radio-list dialog; retain the download details sheet as the place for secondary file actions. The existing library/floating action cluster remains the signature. No extra decoration, gradients, blur, icons-as-badges, or navigation destinations.
