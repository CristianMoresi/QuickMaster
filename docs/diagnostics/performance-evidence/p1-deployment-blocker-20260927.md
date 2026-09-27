# Historical P1 elevation cancellation — resolved

Resolved after the user's renewed «Continúa» authorization. Deployment completed
2026-09-27 09:10:57 +02:00, followed by clean normal-user EXE startup and installed
acceptance. See [deployment](p1-deployment-20260927.json) and
[installed acceptance](p1-installed-acceptance-20260927.json). Original event below.

Observed 2026-09-27 06:12:51 +02:00. The normal PowerShell `Start-Process`
invocation with `-Verb RunAs -WindowStyle Hidden` returned exit 1:

> El usuario ha cancelado la operación.

This is the operating system's reported result; it does not establish whether
the dialog was explicitly dismissed or expired. No elevation retry or workaround
was attempted. The elevated deployment script did not run. Its preflight receipt
still says `IMAGE_VALIDATED`, not `DEPLOYED_AND_VERIFIED`.

- Validated image: `E:/Code/Projects/JA-DAW/QuickMaster-Integration/dist/performance-p1-fixed-20260927/QuickMaster`.
- Final JAR SHA-256: `ec80915fe0e5fc773de33fffde3eb82f9ccaf640e0f38ee2fc137f7c112ca73a`.
- Image: 201 files, Temurin 25.0.4.7, DSPark Java 0.2.
- Program Files JAR rechecked unchanged: `b01ed4faf56ac0a275b59a560bef6572378ae29306195b9ff16d5cf8c1fdd5c3` (P0).
- No new installed-image backup was created by this attempt.
- Complete clean build: 715 tests, zero failures/errors/skips; 106 musical
  variants; reopened package conformance PASSED with 94 readings.

At that point, new user authorization was required before another elevation attempt:
run
`tools/Deploy-Portable.ps1` against the exact image/hash above, verify the installed
EXE startup, and repeat installed source-race, Leveler, waveform and Beat Comp
acceptance. The image was not reported as delivered before those gates passed.
