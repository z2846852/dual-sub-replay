# Compact listening practice, story sorting and update transfer metrics

Status: Approved for implementation (owner feedback, 2026-11-24)
Release intent: patch (changes shipped Android code, not tests-only)
Related: [sentence recall](2026-11-23-offline-listening-sentence-recall.md)

## Goal and acceptance

- On the offline listening screen, compact the sentence-recall card by reducing redundant explanations, vertical whitespace, control footprint, and oversized full-width rows. Retain original pronunciation controls, editable keyboard response, red-only hint after the second failed check, deliberate answer reveal, correction option, navigation and clickable status segments. Put the story overview/list immediately after the active practice card so it is visible without scrolling past sync or word review.
- Story list sorts by **most recently opened** by default; selectable level ascending, level descending, and title. Display each pack's level and compact sentence-level progress. Persist sorting and last-use timestamps locally across restarts; never overwrite source manifests to store usage.
- Put manual GitHub sync and the separate word-recognition review at the bottom. Keep settings accessible with its existing header affordance, not obstructing learning flow.
- A word-exact match on the edited sentence automatically checks as passed without an extra tap, turns the **input border, check button, result and that segment into green**; no mismatch is automatically recorded. Preserve the input, original first-attempt statistics and privacy; do not treat an IME transcription as acoustic pronunciation quality.
- Include a small local persisted checkbox "Auto next on pass", off by default. After an automatically or manually passed sentence, if checked, display the green feedback briefly then advance once to the next sentence using the same one-shot Google voice path; do not auto-advance past the final sentence or loop, and do not overwrite another sentence's progress. If unchecked, remain on the passed sentence.
- During APK or delta download, show **actual transferred/total download bytes** and measured speed (bytes/sec), updated smoothly and safe for resumed, retried and patch-fallback transfers. When transfer size is unknown, do not invent one. No new network calls, permissions, or endpoints.
- Keep all supported interface languages in sync, cover auto pass / auto next eligibility / story sort / progress metrics with pure unit tests.

## Implementation

1. Add persistence for story usage, story sort and auto-next preference; extract deterministic sort/next helpers with JUnit tests.
2. Reorder list/secondary tools and compact recall controls. Use explicit success colors via app theme roles (dark and light).
3. Add monotonic transfer progress fields in update state and render them in both one-tap and conventional update UI, including patch size.
4. Verify final branch formatting, Kotlin compile, focused tests, PR workflow and one-tap personal build after owner's main merge.

## Validation notes

- Pending implementation. Do not mark hardware/input-method behavior validated without a physical device check.

## Owner correction: non-blocking formatting and equivalent contractions

- 2026-11-24: This personal fork does not need a mandatory style-only formatting gate. Android CI still requires Kotlin compilation, complexity analysis, unit tests, Android lint, debug/release builds, and managed-device tests. Only `formatCheck` is removed from the blocking command.
- Sentence comparison treats common English spoken contractions as their full forms: `there's` = `there is`, `I'm` = `I am`, `you're` = `you are`, `don't` = `do not`, etc., in both directions. Contractions normalize before edit-distance alignment, so a different article or tense still produces an error. Ambiguous possessives (`John's`) are **not** expanded. For ambiguous `'s` and `'d`, the narrow `is` and `would` interpretations apply; this is deliberately not general semantic grading.
- Unit tests cover both-direction equivalence, apostrophe variants, and actual differing words preserved. No acoustic pronunciation evaluation is implied.
