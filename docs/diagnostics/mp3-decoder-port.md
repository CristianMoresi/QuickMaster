# MP3 decoder port — implementation and validation

Status: implemented, fully tested and deployed locally on 2026-09-29.
The accepted development build below is the pre-release baseline for 1.3.4.
The final release has its own [validation record](../releases/1.3.4-validation.md).

The user confirmed proceeding after the native limitations were identified.
Keep QuickMaster's public `Mp3File` API intact. Isolate the native-derived
decoder and pin its provenance so future DSPark corrections can be reviewed
and replayed without changing application callers. Do not hide divergence:
record reproduction cases for the upstream team before claiming conformance.

## Scope

Port the MPEG-1 Layer III decoder in DSPark C++ `IO/Mp3File.h` at
`a8556aa7066d52b2aa22b0078cc976f3344a4869` into the dependency-free Java library.
Keep the existing LAME encoder: this task changes import, not export voicing.

The native decoder supports 32/44.1/48 kHz MPEG-1, not MPEG-2/2.5. Preserve those
existing lower-rate imports through an explicitly separate JLayer float-output
adapter. Neither path may quantize to PCM16 or clip decoded floating headroom.
Do not silently fall back to another decoder when an MPEG-1 decode fails.

## Plan and acceptance

1. Pin the native source and port Huffman tables, reservoir, scalefactors,
   requantization, stereo, reorder, alias reduction, hybrid and synthesis banks.
2. Parse frame boundaries and ID3/Xing/Info/VBRI data with checked lengths.
   Apply validated gapless delay/padding, preserve channels/bitrate, bound
   allocation and support cancellation. Reject truncated/corrupt audio instead
   of presenting a silently shortened master as a successful import.
3. Compare deterministic synthetic fixtures against compiled DSPark and an
   independent float decoder. Exercise mono/stereo, rates/bitrates, CBR/VBR,
   reservoir, short blocks, metadata/gapless, sub-16-bit signals and overshoots.
4. Integrate into QuickMaster, removing the PCM16 decoding SPI path. Verify
   trim/reset, metadata, export/reimport and failed-load atomicity.
5. Run all library and application tests, build the Windows image, deploy to
   Program Files, check matching JARs/clean startup and repeat installed checks.

Intentional native-port differences: no synthesis clamp; strict container/bit
bounds and cancellation; Huffman lookup trees instead of repeated linear scans.
Any additional conformance corrections must be recorded with failing fixtures.

Working evidence and pinned native sources: `dist/mp3-port-20260929/`.
Synthetic fixtures contain no private or third-party music.

## Port boundary and reproducibility

- Native file: `IO/Mp3File.h`, Git blob
  `4ce4fde5f4abe4bf839b8d74610444c4880ddae0` at the commit above. The last
  commit changing that file is `9e4bba6b3241efebcb932a9199bd6399207e0096`;
  the path-specific upstream check on 2026-09-29 found no later revision.
- Java: `com.dspark.io.Mp3Tables`, `Mp3Stream`, `Mp3Decoder`. Public application
  `com.quickmaster.audio.Mp3File` constructors, `load`, `save`, bitrate/VBR
  accessors and `AudioFile` editing/reset contract are unchanged.
- DSPark Java 0.2.4 JAR SHA-256:
  `4150ba7f460fc0d2848d365954de06c465a2c7dd372c11eb2fbe7fd32b98c1e6`.
  All **49 pre-existing 0.2.3 class files are byte-identical**. Only I/O classes
  are added; the Leveler/FFT/EQ/clip/limiter boundaries are not changed.
- Build with Temurin 25.0.4.7 targeting Java 17. The native diagnostic was
  built with MSVC C++20, `/O2 /fp:precise`, against the unmodified pinned source.
- Eight synthetic LAME-encoded fixtures plus sixteen independently constructed
  intensity-stereo streams are committed under the vendor test resources.
  Their float PCM oracles use FFmpeg 9.0.2 `mp3float`; the quiet fixture also
  has a separate JLayer 1.0.1 float oracle. Generators live in `tools/diagnostics`.

MPEG-2/2.5 remains an explicit compatibility adapter in the application, not a
claim that DSPark implements those versions. Its custom `Obuffer` captures
JLayer's floats before short conversion and reverses the decoder's actual
**32700** synthesis scale (not 32768). MPEG-1 errors never trigger fallback.
The decoder itself is never an audio-device operation. Import publishes the
new format/samples only after successful validation and preserves source bytes.

## Measured results

| Check | Result |
|---|---|
| Six LAME MPEG-1 signals | Exact independently decoded gapless frame counts |
| Normal-level multisine/transient/headroom signals vs FFmpeg | Maximum error <= 3.160e-6; relative error <= -115.68 dB |
| Sixteen intensity-only and MS+intensity, long/short/mixed cases | Maximum error <= 2.472e-6; relative error <= -114.28 dB |
| Below-PCM16 quiet signal vs JLayer float | Maximum error 1.183e-11; nonzero quiet detail retained |
| Same quiet signal vs FFmpeg | Maximum error 1.335e-7 (-137.49 dBFS); documented independent-decoder difference, not a reason to quantize |
| Over-full-scale fixture | 10,014 samples above nominal full scale retained; no import clamp |
| Extended rate/bitrate matrix | 96/96 pass: 84 MPEG-1 rate/channel/bitrate combinations and 12 MPEG-2/2.5 rate/channel cases |
| Private `By Now` derivative, MP3 VBR q0 | 13,440,001 stereo frames; maximum difference 3.189e-6, relative error -123.322 dB vs FFmpeg |
| Full 280-second private-track decode | 1.155 s in one local run during other tests; not a benchmark guarantee |
| Library suite | 171 tests, no failures, errors or skips |

Bounds tests cover truncated files, missing bit reservoirs, malformed side
information and tags, checked allocations, cancellation, concurrent decoders,
ID3 removal, mono CRC conventions, CBR/VBR metadata and APE trailers. Application
tests cover all MPEG versions, failed-load atomicity, reset and source preservation.
These are regression/conformance checks over the documented corpus, not a claim
to certify every possible MP3 bitstream. Free-format and MPEG Layers I/II are
not supported by this Layer III importer.

The private WAV was read only. Its temporary encoded derivative and PCM
references remain under ignored `dist/`; no private audio is committed or
included in the app. Neither reference checks nor UI probes played Windows audio.

## Confirmed native differences to report upstream

Ready-to-send report: [DSPark MP3 findings](dspark-mp3-upstream-report.md).
It has **not** been sent or committed to the upstream repository.

1. Preserve finite float synthesis above +/-1 instead of clamping imported audio.
2. Validate FFmpeg mono gapless CRC with its zeroed CRC field/fixed-190-byte
   convention, including virtual padding for a short tag frame. Never include
   audio bytes from the next frame in that CRC.
3. Apply intensity only above the last coded nonzero right-channel band, per
   short window; preserve holes below that point and support mixed/MS cases.
4. Reject incomplete frames and unavailable reservoir references rather than
   returning a complete-looking decode containing concealed/truncated data.

Java also uses checked contiguous frame parsing, Huffman prefix trees,
cancellation, APE/footer bounds and a finite/allocation-limited output contract.
Those safety/interface adaptations are kept explicit, not mislabeled as a
byte-for-byte native translation.

## Updating from DSPark later

Keep the application API and fixture corpus stable. Compare the upstream
`IO/Mp3File.h` change against the pinned blob, review each of the four local
corrections above, and port compatible fixes into `com.dspark.io` only.
Rerun independent PCM oracles, malformed input tests, old-class byte comparison,
the entire library/application suite and installed-image checks before updating
the dependency pin. Do not copy the native clamp or a regression back merely
to force native numerical identity. No automatic upstream mutation is configured.

## Final local delivery

This section records the accepted development build before release packaging.

- Full clean QuickMaster build: **936 tests / 140 classes**, zero failures,
  errors or skips; official ITU/EBU fixtures and packaged attestation included.
- DSPark: **171 tests / 38 classes**, zero failures, errors or skips.
- Windows app image generated with the supported Temurin 25.0.4.7 runtime;
  only DSPark 0.2.4 is packaged and the PCM16 MP3 SPI is absent.
- Deployed to `C:\Program Files\QuickMaster`; core JAR SHA-256:
  `b93f7d49255cded28fc6535f19638adc8d5ee3bb2cfc8f224eddc7d43ac4e1cb`.
  Build and installed hashes agree. Installed EXE launched with clean
  `Application starting (JavaFX)` / `Controller initialised` log at 18:47:02.
- **386 additional regression tests run against installed JARs**, all pass.
- Installed FXML/controller imports the private full-track MP3, snapshots the
  tilted analyzer at both sizes and confirms unchanged PCM, preset, measurements
  and analysis generation. **97 installed MP3 reference comparisons pass**:
  the 96-format matrix plus the private full track. No Windows audio playback.
- Recoverable prior image: `C:\Program Files\QuickMaster-backup-20260929-184700`.
  Evidence: `dist/mp3-port-20260929/`, including suite XML, deployment result,
  installed test logs and `installed-ui/` snapshots. Local build version:
  `1.3.4-SNAPSHOT`; the 1.3.3 release was still unchanged at this checkpoint.
