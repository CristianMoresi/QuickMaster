# Fresh export metadata

Pre-release acceptance: verified and deployed locally on 2026-09-29 as
1.3.5-SNAPSHOT. Final release evidence is recorded separately in
[1.3.5 validation](../releases/1.3.5-validation.md).

## Policy

Every newly saved WAV or MP3 contains exactly two descriptive fields, generated
for that export: UTC creation/encoding time and
`Made with QuickMaster by Cristian Moresi`. No original metadata is inherited:
title, artist, album, copyright, artwork, comments, lyrics, ReplayGain tags,
broadcast provenance, original timeline, private and unknown tags are omitted.
The credit describes software; it does not attribute authorship of the music.

WAV uses one RIFF `LIST/INFO` with `ICRD` (ISO-8601 UTC timestamp) and `ISFT`.
MP3 uses one ID3v2.4 header with UTF-8 `TDEN` (encoding time, UTC) and `TSSE`.
There is no ID3v1 or APE export tag. Necessary container/sample-format headers
and the encoder's MPEG/timing data are not descriptive tags and remain intact.

## Implementation

`ExportMetadata` is a closed allowlist, created per save and serialized directly
by `WavFile` / `Mp3File`. It has no source path or source-tag input. WAV sizes and
word padding include the fresh INFO list; MP3 frames follow the fresh ID3 header.
Jump3r 1.0.5's `LameEncoder.nInitParams` already disables automatic ID3 writing.
No extra audio pass, file reread, decoder invocation or resampling is added.

The old `MetadataPreserver` and its preservation-only tests are removed; their
history remains in Git. Both individual and batch export use the same atomic
transaction. The legacy three-argument `AudioExport.write` delegates to clean
export and deliberately ignores its source path for caller compatibility.
Original files are never cleaned in place. Only an explicitly selected export
destination is replaced, atomically; failure/cancellation keeps it unchanged.

## Verification

- New policy tests fail against 1.3.4's behavior before the implementation.
- Independent RIFF/ID3 parsers require exactly the two fresh fields and reject
  duplicates/extra chunks, tags and original identifying bytes.
- WAV integer 16/24/32 and float 32, mono/stereo; MP3 sources with ID3v2.3/2.4,
  ID3v1 and APE plus artwork/private/text fields; same- and cross-format batch
  export, explicit same-file overwrite, missing original path and cancellation.
- PCM samples and source files remain unchanged by tagging; float headroom,
  odd RIFF sizes, UTC seconds and date serialization are covered.
- `ExportMetadataAudit` compares raw WAV and encoded MPEG payloads against the
  previous installed application in fresh JVMs (same dither sequence). Its
  optional private excerpt stays under ignored `dist/`; it uses no audio device.

## Completed acceptance

- Clean `mvnw clean package` with the authorized ITU/EBU signals:
  **947 tests / 140 classes**, zero failures, errors or skipped tests.
- Vendored DSPark: **171 tests / 38 classes**, zero failures, errors or skips.
- JDK 25.0.4 Windows application image: **197 files** copied and verified in
  `C:\Program Files\QuickMaster`. Installed executable started cleanly at
  **2026-09-29 23:08:33 Europe/Madrid** (`Application starting (JavaFX)` and
  `Controller initialised.`); the verification launch was then closed.
- **397 tests** passed against the installed JAR, without workspace production
  classes on the classpath. These supplement, rather than replace, the full suite.
- **35 installed exports** across mono/stereo, 32/44.1/48 kHz, integer WAV
  16/24/32, float WAV 32 and MP3, including a private musical excerpt:
  FFprobe independently found exactly two descriptive fields in each file.
- All **35 raw WAV/MPEG payload hashes** match the previous installed 1.3.4
  build bit for bit. This verifies that the metadata change itself does not
  alter encoding or samples; it does not imply that MP3 encoding is lossless.
  The private source file remained unchanged. No listening session is claimed.

Installed/build core JAR SHA-256:
`69f3abf5bca7b5821a60c4bf21532836686ec41d3c620bb3bf90acff09ea4782`.

The previous application image remains recoverable at
`C:\Program Files\QuickMaster-backup-20260929-230831`.
Evidence includes `full-suite-totals.json`, `dspark-suite-totals.json`,
`deployment-result.json`, `installed-junit.log`, `installed-payloads.log` and
`installed-metadata.json` under the working evidence directory below.

Format references: [ID3 frame definitions](https://id3.org/id3v2.4.0-frames),
[ID3 structure and UTC timestamps](https://id3.org/id3v2.4.0-structure),
[Microsoft RIFF layout](https://learn.microsoft.com/en-us/windows/win32/xaudio2/resource-interchange-file-format--riff-).

Working evidence: `dist/fresh-export-metadata-20260929/`.
