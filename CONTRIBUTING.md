# Contributing to QuickMaster

Thanks for your interest in improving QuickMaster. This guide covers how to build
the project and the conventions used in the codebase.

## Building

QuickMaster is a Maven project targeting **JDK 17+**.

```bash
mvn clean javafx:run   # build and launch the app
mvn test               # run the unit test suite
```

The audio engine lives in a separate library, **DSPark for Java** (`com.dspark:dspark`), a
Java port of the [DSPark](https://github.com/CristianMoresi/DSPark) C++ DSP library. Install
it to your local Maven repository first:

```bash
mvn -f path/to/dsp/pom.xml install
```

## Project layout

| Package | Responsibility |
|---------|----------------|
| `com.quickmaster.audio` | Audio model and WAV/MP3 file I/O |
| `com.quickmaster.processing` | DSP pipeline and the `AudioProcessor` lifecycle; sub-packages `eq`, `dynamics`, `clip`, `limit`, `analysis` |
| `com.quickmaster.playback` | Real-time streaming playback engine |
| `com.quickmaster.config` | Configuration persistence and logging |
| `com.quickmaster.ui` | JavaFX controller, FXML and stylesheet |

## Conventions

- **Java**: 4-space indentation, Allman braces (matching the existing code).
- **Audio**: samples are interleaved 32-bit `float` in the range `[-1.0, +1.0]`.
- **Tests**: JUnit 5; cover DSP behaviour with deterministic buffers and, where a
  reference value exists (e.g. LUFS), assert against it.
- **Documentation**: Javadoc explains *why* a design choice was made, not just *what*
  the code does.

## Reporting issues

Please include your OS, JDK version, and a minimal way to reproduce the problem
(ideally the audio file characteristics: format, sample rate, bit depth, channels).
