# ReChord agent instructions

Read the [ReChord engineering standards](docs/RECHORD-STANDARDS.md) before planning or editing. It is the canonical guide for coding, UI, playback, add-ons, and delivery.

- Keep planning and investigation read-only until the user approves implementation.
- Treat Portrait and Landscape — including DAP (`560–720 × 360 dp`) and 720p Car Head-Unit (`640 × 360 dp`, `853 × 480 dp`, `1280 × 720 dp`) layouts — as equal first-class targets in every UI, design, logic, and animation change. Proactively audit for animation/state gaps, Frame-1 composition vs. `LaunchedEffect` timing, touch/overlay hitboxes, row text boundaries, per-frame GPU/shader allocations, and edge cases before presenting a plan or pushing a Preview APK.
- Work on the current or explicitly named branch. Ask before creating or switching branches. Merge and run release workflows only as authorized.
- Preserve existing app data, package identities, signing keys, audio behavior, and compatibility with query-only add-ons. Verify affected entry points and module builds (`:sharedUi:jvmTest`, `:sharedUi:compileDebugKotlinAndroid`, `:app:compileDevDebugKotlin`, and `:tv:compileDevDebugKotlin` when shared code or `BuildConfig` fields affect TV) before reporting completion.
- Follow the approved scope and report what was verified. For documentation-only changes, skip push-triggered builds (`[skip ci]`) unless requested.
