# Offline listening: sentence-by-sentence voice-input recall

## Status

Approved for implementation — requested by the fork owner on 2026-11-23. This authorizes implementation, not merging or publication.

## Context / problem

The offline Listening screen currently plays every dialogue line continuously. The learner wants to hear one line, stop, dictate their recall with the *phone keyboard's speech-to-text input*, inspect word-level errors, and explicitly proceed. No app-owned microphone, speech recognizer, AI server, or new voice provider is necessary.

The current default sound path is **Google natural-voice MP3**, via `ListeningNaturalVoice.cachedOrDownload`, using cached MP3 offline after a successful fetch. Android offline TTS is only an **explicit** fallback; recall must keep this behavior unchanged.

Related: [Listening screen](../../app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningScreen.kt), [speech service](../../app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningSpeechService.kt), [existing video dictation](2026-11-09-dictation-play-mode.md).

## Goals

- Offer a mode switch in Offline Listening: continuous playback / sentence recall.
- In recall mode, play exactly one selected sentence using the current Google-natural-voice and cache preference, then stop and await input, never automatically playing the next sentence.
- Provide an ordinary editable text field compatible with Android IME dictation (the learner chooses the keyboard's microphone).
- Upon Check, reveal the original, show expected and dictated text aligned word-by-word, with missed/replaced/extra words conspicuously marked in the theme's error color.
- Treat capitalization, sentence punctuation, and whitespace as irrelevant, but **not** articles, prepositions, inflection, or other distinct words.
- Allow Replay, Try again, Previous, and Next. Next plays the new line once. Keep sentence taps for word-card saving after reveal.
- Continuous mode must remain unchanged.

## Non-goals

- Do not add in-app speech recognition or record the learner's voice.
- Do not claim to measure pronunciation from keyboard-transcribed text; Gboard and other IMEs may autocorrect or misrecognize.
- Do not change the FSRS score or silently mark words as mastered based on transcription equality.
- Do not change YouTube's WebView or its separate video dictation mode.

## User-visible behavior

The current dialogue pack is used; on enabling Recall it starts the displayed line. The transcript stays hidden while listening, including through replay. After playback completion the UI shows an editable answer box; the keyboard's voice dictation types normal text. Check is manual (also keyboard Done) and may be used after playback has stopped. The original and transcribed rows then appear; the original/answer mismatch is colored and the number of differences is reported. Next starts the next line once, clears the answer, and waits again; repeated attempts do not advance automatically. Switching mode or selecting a different pack cancels the prior line and prevents stale audio from continuing.

If Google audio is uncached and unavailable, keep the existing error and explicit “Try local” fallback: retrying local TTS in Recall must still stop after one sentence.

## Technical constraints / invariants

- Do not change default `listening_natural_voice=true` or replace `ListeningNaturalVoice`.
- Use the existing `ListeningSpeechService` for both MP3 and Android local TTS; add a one-line completion behavior gated by an explicit playback intent extra.
- No network-based comparisons, permissions, or third-party services; word alignment is pure Kotlin.
- Keep user-visible new strings in area-specific resources, with all supported interface-language resource copies.
- No change to local learner data schemas or GitHub sync.
- Preserve existing continuous playback behavior and old callers' default playback parameters.

## Proposed approach / plan

1. Add a `oneShot` playback option and end-of-line completion state in the existing service. The MP3 and local TTS completion callbacks share the same end path.
2. Implement deterministic, punctuation/case-insensitive word-level edit-distance alignment with insertions, deletions, and replacements; test articles, missing words, extras, punctuation, and repeated words.
3. Add a purpose-built recall card next to the existing karaoke card and mode switch in `ListeningScreen`. Reuse word-card handling on the unveiled target text. Preserve the original continuous mode unchanged.
4. Add localizations and document resulting behavior in the technical context.
5. Open a focused PR with default patch intent; CI and physical-device verification follow.

## Acceptance criteria

- [ ] Continuous mode still automatically proceeds line by line; default voice is Google MP3/cache.
- [ ] Recall plays exactly one line then stops on both Google cached MP3 and explicitly selected device TTS.
- [ ] Switching pack/mode does not leave the previous sentence playing.
- [ ] Voice-input text can be pasted/dictated using any normal Android keyboard; no new microphone permission.
- [ ] `I went to the shop.` vs `i went to a shop` reports the/the-to-a mismatch only; `I went to the shop!` matches ignoring punctuation and case.
- [ ] Insertion/deletion does not mark every subsequent word as wrong; contractions and repeated words are deterministic.
- [ ] Replay/Previous/Next/Try again never auto-advance before explicit Next; words remain tappable after Check.
- [ ] Documentation and CI results are recorded, with physical voice-input and natural speech checked on a device separately.

## Validation plan

| Category | Command/scenario | Environment |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest --tests 'com.kienhoang.dualsubreplay.listening.ListeningRecallCheckTest'` | Android Gradle CI |
| Android CI | `./gradlew formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | GitHub workflow |
| Device | Play Google natural voice (network/cached), dictate via installed keyboard mic, check output, replay, next, switch pack, rotate | Physical Android |
| Fallback | Force unavailable Google, explicitly use Try local, verify end-of-line pause | Physical Android |
| YouTube | No changed YouTube path | Not applicable |

## Risks / edge cases

- IME speech input may insert partial interim text before committing; comparison occurs only on an explicit check.
- Dictation accuracy is not pronunciation accuracy; a correct string doesn't prove the learner produced all phonemes.
- A subtitle/transcript may itself be wrong; mismatch is textual, not semantic.
- Short lines can inflate percentage scores; report exact differences rather than pretending it is a CEFR level.

## Release intent

`release:patch` (default): a backwards-compatible opt-in study mode reaching the APK; no Gradle version change.

## Implementation result

Pending.

## Validation result

Pending.
