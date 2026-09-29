# Stereo Image interaction update — 2026-09-29

## Scope and acceptance

Keep the approved 0–100 Generation range and automatic full-quality harmonics.
New sessions start EQ → Stereo Image → Dynamics → Clip → Limit. Explicit saved
orders remain untouched. All three processing sections still start off.

1. Replace Generate Low Frequencies with **Generated Stereo Low Cut**: Off at the
   left endpoint/default, logarithmic 20 Hz–5 kHz, compensated linear-phase FIR
   applied only to the new delta. Old presets retain their former bass exclusion.
2. Add **Side Gain**, -12…+12 dB, 0 dB default. Final manual gain on combined Side
   after Leveler/Guard, leaving Mid unchanged. It is deliberately independent of
   the regulator; positive trim may exceed the guard's target. No implicit gain
   normalization.
3. Use aligned three-column cards with consistent knob geometry and integrated
   On/Off switches, a compact balance readout, and a common two-column slider row.
   No scattered section checkboxes or asymmetric extra controls inside cards.
4. Stop regenerating the full moving/harmonic delta for every control edit.
   Use a bounded, input-identity/rate-scoped immutable cache shared by snapshot
   forks. Generation amount and regulation edits reuse it; changing the low cut
   reuses generation and only rebuilds filtering. Changed upstream audio must
   invalidate it. Reuse generated energy for planning instead of a second
   synthesis. Cancellation never publishes partial/stale cache entries.
5. Keep the independent short-window audition worker and exact final rendering.
   Measure both control-to-preview and whole-song warm-edit time. UI must remain
   usable while final statistics update; previews never replace export data.

Tests: default/legacy preset migration, filter response and mono sum, Side-only
gain and bypass, cached/cold equivalence, source/rate/settings invalidation,
cancellation, preview/final alignment, real-track edit timings, UI layout at
normal/compact sizes, A/B/Undo and rapid changes. Then full automated suite,
Windows package/image, Program Files deployment, matching files and clean EXE
startup, with acceptance repeated against the installed JAR. No release/push
requested; retain all unrelated work and private source audio unchanged.

Status: implemented, fully tested, packaged, deployed and verified against the
installed JAR. Results: [stereo-interaction-results.md](stereo-interaction-results.md).
