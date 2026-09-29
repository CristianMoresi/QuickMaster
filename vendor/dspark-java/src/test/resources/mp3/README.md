# Synthetic MP3 reference vectors

All signals and bitstreams are generated for this repository; no music,
third-party recordings or private user audio is included.

- Eight mono/stereo multisine, transient, quiet and over-full-scale signals:
  `tools/diagnostics/Generate-Mp3Fixtures.ps1` from the repository root.
  FFmpeg 9.0.2 (Gyan Windows build), `libmp3lame` encoder, 0.413 seconds.
- Sixteen constructed MPEG-1 intensity-stereo streams:
  `java tools/diagnostics/Mp3IntensityFixtures.java <output-directory>`.
  Long, short, mixed and mixed-low blocks; intensity alone or with MS;
  scalefactor 3 and intensity-disabled 7; spectral holes below the last
  nonzero right-channel band. No reservoir or gapless metadata in these cases.
- `.ffmpeg.f32le`: independent float reference, decoded with
  `ffmpeg -c:a mp3float -i <input.mp3> -f f32le -c:a pcm_f32le <output>`.
  Little-endian, interleaved, native sample rate/channels.

Tests check exact frame counts, float headroom and sample error below 4e-6
(-108 dBFS absolute). Normal-level fixtures also require relative error below
-100 dB. The very quiet fixture differs from FFmpeg by at most 1.335e-7;
an independent JLayer 1.0.1 float decode agrees with the port within 1.183e-11.
Its additional `.jlayer.f32le` oracle requires a 2e-11 maximum difference,
so the FFmpeg comparison's 2e-7 bound does not mask a loss of quiet detail.
Generate that vector with `tools/diagnostics/Mp3QuietReferenceAudit.java` and
JLayer 1.0.1 on the classpath. No bit-identical independent-decoder claim.

The two lower-rate MPEG-2/2.5 files exercise QuickMaster's explicitly separate
JLayer float adapter, not the MPEG-1-only DSPark port. The Java library rejects
those versions explicitly. Application-level tests read this shared corpus.
