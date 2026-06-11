package com.quickmaster.ui;

import com.dspark.analysis.LoudnessMeter;
import com.dspark.effects.MasterEqualizer;
import com.quickmaster.audio.AudioFile;
import com.quickmaster.audio.AudioFileException;
import com.quickmaster.audio.AudioFormatDetector;
import com.quickmaster.audio.MetadataPreserver;
import com.quickmaster.audio.Mp3File;
import com.quickmaster.audio.WavFile;
import com.quickmaster.config.AppConfig;
import com.quickmaster.config.AppLogger;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.processing.dynamics.AnalysisDynamicsProcessor;
import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.dynamics.BeatCompProcessor;
import com.quickmaster.processing.eq.EqualizerProcessor;
import com.quickmaster.processing.FadeProcessor;
import com.quickmaster.processing.dynamics.LevelerProcessor;
import com.quickmaster.processing.limit.MultibandLimiterProcessor;
import com.quickmaster.processing.limit.BroadbandLimiterProcessor;
import com.quickmaster.processing.clip.HardClipProcessor;
import com.quickmaster.processing.clip.SoftClipProcessor;
import com.dspark.effects.Saturation;
import com.quickmaster.processing.dynamics.PeakCompProcessor;
import com.quickmaster.processing.PeakNormalizer;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.dynamics.PunchProcessor;
import com.quickmaster.processing.eq.AutoEqProcessor;
import com.quickmaster.processing.analysis.LiveSpectrum;
import com.quickmaster.processing.analysis.SpectrumAnalysis;
import com.quickmaster.processing.analysis.TrackAnalysis;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleConsumer;

/**
 * JavaFX controller wiring the FXML scene to the application
 * domain. Handles user interaction, processor configuration,
 * playback control, waveform rendering, and the export flow.
 * <p>
 * <b>Responsibility split.</b> This controller owns one
 * instance of each domain object (the processors that make up the
 * mastering chain, the {@link ProcessingPipeline} that composes
 * them, the {@link AudioPlayer}, and the {@link AppConfig}
 * singleton). Integrated loudness is measured on demand with the
 * DSP engine's {@code LoudnessMeter}. Each {@code @FXML} field is a
 * reference to a node defined in {@code main-view.fxml} and
 * injected by JavaFX after the scene is loaded. Event handlers
 * are also declared in the FXML and resolved by name to the
 * matching {@code @FXML} methods here.
 * <p>
 * <b>Threading.</b> Long-running work (audio decoding, LUFS
 * measurement, export to disk, peak re-analysis) runs on
 * background {@link Task}s so the JavaFX Application Thread
 * never blocks. Task completion handlers run back on the FX
 * thread automatically. The {@link AudioPlayer} runs its own
 * dedicated playback thread and reports position changes via
 * JavaFX properties that the controller listens to.
 * <p>
 * <b>Slider semantics.</b> Each processor slider has a listener
 * that pushes the new value into the corresponding processor
 * field immediately. As a result, moving a slider during
 * playback is audible on the next audio buffer (≈ 23 ms at
 * 44.1 kHz). The two trim sliders are an exception: they only
 * update their label display. The actual trim is applied at
 * play and export time by calling {@code reset()} on the
 * {@link AudioFile} and then {@code trim()} with the slider
 * values; this keeps trim non-destructive.
 * <p>
 * <b>Audio-style keyboard and mouse shortcuts.</b> Every slider
 * in the interface honours the keyboard and mouse modifiers
 * commonly found in professional audio plug-ins, so the
 * application feels native to users coming from professional
 * DAWs and plug-in formats (VST, AU). The mapping is uniform
 * across all sliders:
 * <ul>
 *   <li><b>Mouse wheel</b> on a slider, or <b>arrow keys</b>
 *       while a slider has focus, adjust the value by one step.
 *       Wheel up / arrow up / arrow right increase the value;
 *       wheel down / arrow down / arrow left decrease it. On
 *       systems where the operating system converts
 *       Shift+wheel-vertical into wheel-horizontal (macOS, some
 *       Windows mouse drivers, Wayland), the handler falls back
 *       to the horizontal delta so the gesture still works.</li>
 *   <li><b>Click and drag</b> on a slider with Shift or
 *       Ctrl/Cmd held snaps the value to the nearest multiple
 *       of the corresponding step size as the cursor moves,
 *       just like Alt-drag or Shift-drag in a DAW. With no
 *       modifier the slider behaves continuously, like the
 *       JavaFX default.</li>
 *   <li>The <b>step size</b> in every mode depends on the
 *       modifier held during the action: no modifier = coarse,
 *       Shift = standard (one dB exactly on gain sliders),
 *       Ctrl (Windows/Linux) or Cmd (macOS) = fine. The
 *       cross-platform Ctrl/Cmd detection is handled
 *       transparently by JavaFX's {@code isShortcutDown()},
 *       which maps to {@code Cmd} on macOS and {@code Ctrl} on
 *       Windows/Linux. No operating-system check is needed in
 *       application code.</li>
 *   <li><b>Double-click</b> on a slider (no modifier needed),
 *       or <b>Ctrl/Cmd + single click</b>, returns the value
 *       to the parameter's default - 0 dB on gain sliders, 0 s
 *       on fades and trims, 0 dBFS on the peak target. This
 *       mirrors the alt-click reset found in many DAW
 *       plug-ins.</li>
 * </ul>
 * The per-slider step sizes are listed in {@link #initialize()}
 * where the shortcuts are wired up, so they can be tweaked in
 * one place. Steps are rounded to two decimal places after
 * each application to avoid floating-point drift across
 * repeated wheel events (so eight Shift-wheel clicks on a gain
 * slider land exactly on {@code -8.00 dB}, not
 * {@code -7.9999996 dB}).
 * <p>
 * <b>Peak re-analysis.</b> The normalizer's gain factor is
 * computed once when playback starts. If the user later
 * adjusts an upstream control (e.g. a fade), the previous
 * factor becomes stale: the audio reaching the normalizer
 * changes, but it is still applying the original factor. The
 * "Re-analyze peak" action simulates the upstream Fade inline,
 * on a copy of the source samples, without touching the shared
 * processor instances the audio thread is using. The simulation
 * runs <i>without clamping</i>, so the analysis can find the
 * actual maximum the normalizer needs to bring to target. The
 * new factor lands in the {@link PeakNormalizer}'s volatile gain
 * (held by the underlying DSP engine) and the audio thread reads
 * it on its next buffer. (Offline export analyses the full
 * post-upstream signal, the EQ included, through the pipeline.)
 * <p>
 * <b>Export with format selection.</b> The export action lets
 * the user choose the output format (WAV or MP3) by picking
 * the corresponding extension in the file chooser. When MP3 is
 * chosen, a secondary dialog asks for the encoding bitrate.
 * The default bitrate is intelligent: if the loaded
 * file is an MP3, the default matches its source bitrate; if
 * the loaded file is a WAV, the default is 320 kbps (the
 * highest standard MP3 bitrate, sensible for a mastering tool
 * exporting to a lossy format).
 * <p>
 * <b>Waveform interaction.</b> The waveform canvas accepts
 * both a click (instant seek) and a press-drag-release
 * (continuous scrubbing while the mouse moves). During a drag,
 * the audio position follows the cursor in real time; if the
 * player is playing, the audio jumps live; if it is stopped or
 * paused, only the cursor moves.
 */
public class MainController
{
    /* =========================================================
     *  FXML field bindings - one per fx:id in main-view.fxml
     * ========================================================= */

    // Root container (target for whole-window file drag-and-drop).
    @FXML private BorderPane rootPane;

    // Top bar
    @FXML private Label fileNameLabel;
    @FXML private Label fileInfoLabel;
    @FXML private Label topBpmLabel;
    @FXML private Button undoButton;
    @FXML private Button redoButton;

    // Center: waveform & transport
    @FXML private Pane waveformWrapper;
    @FXML private Canvas waveformCanvas;
    @FXML private Button goStartButton;
    @FXML private Button playButton;
    @FXML private Button stopButton;
    @FXML private Button goEndButton;
    @FXML private ToggleButton abButton;
    @FXML private ToggleButton loopButton;
    @FXML private Label positionLabel;

    // Preset A/B settings toggle (top bar).
    @FXML private ToggleButton settingsAbButton;

    // Trim
    @FXML private Label selectionLabel;

    // Equalizer (unified first block: tone + L/R/M/S routing + dynamics)
    @FXML private CheckBox eqEnabled;
    @FXML private Pane eqCanvasWrapper;
    @FXML private Canvas eqCanvas;
    @FXML private CheckBox autoEqOn;
    private ComboBox<AutoEqProcessor.Target> autoEqTarget;
    private Knob autoEqAmountKnob, autoEqAttackKnob, autoEqReleaseKnob;
    private Node[] autoEqControls;
    private HBox bandMenu;                 // floating mute/erase over the clicked band
    private Button bandMuteBtn;
    private boolean showBandMenu = false;  // visible only after clicking a band
    @FXML private ComboBox<String> eqBandSelector;
    @FXML private ComboBox<MasterEqualizer.BandType> eqType;
    @FXML private ComboBox<MasterEqualizer.Channel> eqChannel;
    @FXML private ComboBox<MasterEqualizer.BandPhase> eqPhase;
    @FXML private ComboBox<Integer> eqSlope;
    @FXML private CheckBox eqDynamic;
    @FXML private HBox eqKnobs;

    // Knob editors (built in initEqUi). The dynamic ranges are SIGNED:
    // positive boosts / expands, negative cuts / compresses. (The Peak
    // Normalizer target is a slider in the right panel, not a knob.)
    private Knob kFreq, kGain, kQ, kThreshold,
            kAboveRange, kAboveRatio, kAboveAtk, kAboveRel,
            kBelowRange, kBelowRatio, kBelowAtk, kBelowRel;
    /** Live detector-level meter beside the threshold knob. */
    private LevelBar levelBar;

    // Fade processor
    // Peak Normalizer (right panel, always at the end of the General block)
    @FXML private CheckBox peakEnabled;
    @FXML private Label peakInputLabel;
    @FXML private Slider peakTarget;
    @FXML private Label peakTargetLabel;
    @FXML private Label peakAppliedLabel;

    // Oversampling (top chain row): toggle + factor selector; drives both the
    // live playback engine and the offline render (measure / export).
    @FXML private ToggleButton osToggle;
    @FXML private ComboBox<String> osCombo;
    private int oversampling = 1;

    // Limit module (multiband + broadband true-peak limiting; UI built in initLimiterUi)
    @FXML private CheckBox limEnabled;
    @FXML private VBox limContent;

    // Processing-chain tab bar + module panels
    @FXML private HBox chainBar;
    @FXML private Region eqPanel;
    @FXML private Region dynamicsPanel;
    @FXML private GridPane dynamicsGrid;
    @FXML private CheckBox dynMasterEnabled;
    @FXML private Region clipPanel;
    @FXML private CheckBox clipEnabled;
    @FXML private HBox clipCards;
    @FXML private Region limiterPanel;

    // Command bar + loudness meters
    @FXML private Label meterLufs;
    @FXML private Label meterShort;
    @FXML private Label meterMom;
    @FXML private Label meterLra;
    @FXML private Label meterPeak;
    @FXML private Label meterGr;

    // Stereo image meters: phase correlation, M/S levels, goniometer.
    @FXML private Label meterCorr;
    @FXML private Label meterMid;
    @FXML private Label meterSide;
    @FXML private Canvas gonioCanvas;

    // Status bar
    @FXML private Label statusLabel;
    @FXML private Label analyzeLabel;
    @FXML private ProgressBar analyzeProgress;
    private int analyzeJobs = 0;

    // Export overlay: a full-window modal blocker shown while an export runs.
    @FXML private StackPane rootStack;
    @FXML private VBox exportOverlay;
    @FXML private ProgressBar exportBar;
    @FXML private Label exportFileLabel;
    @FXML private Label exportPercentLabel;
    @FXML private Label exportTimeLabel;
    private volatile boolean exporting = false;
    private long exportStartNanos = 0L;
    private Task<?> exportTask;

    /* =========================================================
     *  Domain state
     * ========================================================= */

    // Processors in chain order. The unified Equalizer is the first block:
    // with per-band channel routing and flat GAIN bands it does all tone
    // work plus L/R volume, Mid/Side and stereo width itself, so there are
    // no separate modules for those. Chain: EQ → Fade → Peak Normalizer →
    // Limiter.
    private final AutoEqProcessor autoEq = new AutoEqProcessor();
    private final EqualizerProcessor eq = new EqualizerProcessor();
    private final FadeProcessor fade = new FadeProcessor();
    private final PeakNormalizer normalizer = new PeakNormalizer();
    private final MultibandLimiterProcessor multiband = new MultibandLimiterProcessor();
    private final BroadbandLimiterProcessor broadband = new BroadbandLimiterProcessor();
    private final SoftClipProcessor softClip = new SoftClipProcessor();
    private final HardClipProcessor hardClip = new HardClipProcessor();

    // Dynamics: four automatic, analysis-driven compressors that run before the
    // Peak Normalizer. Reorderable among themselves; the Peak Normalizer and the
    // Limiter are always pinned at the end of the chain.
    private final PeakCompProcessor peakComp = new PeakCompProcessor();
    private final BeatCompProcessor beatComp = new BeatCompProcessor();
    private final LevelerProcessor leveler = new LevelerProcessor();
    private final PunchProcessor punch = new PunchProcessor();
    /** Mutable order of the four compressors (the user can reorder them); this
        is the live processor list of the Dynamics chain module. */
    private final List<AudioProcessor> dynamicsOrder =
            new ArrayList<>(List.of(peakComp, beatComp, leveler, punch));
    /** Per-track tempo + onset analysis, shared by Glue and Punch. */
    private final TrackAnalysis trackAnalysis = new TrackAnalysis();
    private final SpectrumAnalysis spectrumAnalysis = new SpectrumAnalysis();
    private final LiveSpectrum liveSpectrum = new LiveSpectrum();

    private final ProcessingPipeline pipeline = buildPipeline();
    private final AppConfig config = AppConfig.getInstance();
    private final AudioPlayer player = new AudioPlayer(pipeline);

    private AudioFile loadedFile;
    private float[] waveformDownsampled;

    /**
     * One undo / redo entry: a destructive sample edit (crop / delete, with
     * the samples captured) or a parameter change (samples {@code null}).
     * Both carry the full chain configuration of the moment, so undo also
     * restores knob positions.
     */
    private static final class EditState
    {
        final float[] samples;            // null = parameter-only entry
        final com.quickmaster.config.ChainPreset params;

        EditState(float[] samples, com.quickmaster.config.ChainPreset params)
        {
            this.samples = samples;
            this.params = params;
        }

        long bytes() { return (samples != null) ? samples.length * 4L : 4096L; }
    }

    /** Undo / redo history (sample edits and parameter gestures). */
    private final java.util.Deque<EditState> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<EditState> redoStack = new java.util.ArrayDeque<>();
    private static final int MAX_UNDO_STEPS = 24;
    /** Byte budget for the history, so long hi-rate files cannot exhaust the heap. */
    private static final long MAX_UNDO_BYTES = 512L * 1024 * 1024;

    /** Chain state at the start of the current parameter gesture (null = no open gesture). */
    private com.quickmaster.config.ChainPreset paramGestureBaseline = null;
    /** True while a preset (or an undo) is being applied, so listeners stay quiet. */
    private boolean applyingPreset = false;

    /** A/B settings slots: the configuration not currently active. */
    private com.quickmaster.config.ChainPreset settingsSlotA = null;
    private com.quickmaster.config.ChainPreset settingsSlotB = null;

    /** Tonal-prefix cache: the analysed signal after the EQ block, so a
     *  dynamics / clip / limit gesture skips re-rendering the (expensive)
     *  tonal stages when they did not change. */
    private long tonalSigCache = 0L;
    private float[] tonalBufCache = null;
    private int tonalStagesCache = -1;

    /** Streaming true-peak detectors for the live PEAK meter (FX thread). */
    private com.dspark.analysis.TruePeak[] liveTp = null;
    /** Smoothed stereo-image readouts (FX thread). */
    private double liveCorrSmooth = 0.0;
    private double liveMidPow = 0.0, liveSidePow = 0.0;

    /** True while the user is dragging on the waveform to scrub. */
    private boolean scrubbing = false;
    /** Fade-handle drag on the waveform: 0 = none, 1 = fade-in, 2 = fade-out. */
    private int fadeDragMode = 0;
    /** Waveform range selection in seconds (negative = none) and drag state. */
    private double selStartSec = -1.0, selEndSec = -1.0;
    private boolean selecting = false;

    /** True while a loadable audio file is being dragged over the window. */
    private boolean fileDragActive = false;
    /** Pseudo-class toggled on the root to highlight the window during a file drag. */
    private static final PseudoClass FILE_DRAG_PSEUDO = PseudoClass.getPseudoClass("file-drag");

    /** Number of equalizer bands the user has added. */
    private int eqBandCount = 0;
    /** Index of the band currently shown in the EQ editor, or -1 for none. */
    private int eqSelectedBand = -1;
    /** Guard: true while pushing values into EQ controls (suppresses listeners). */
    private boolean updatingEqEditor = false;
    /** True while dragging a band handle on the EQ curve. */
    private boolean eqDragging = false;
    /** Axis lock during Alt-drag: 0 = free, 1 = frequency only, 2 = gain only. */
    private int eqDragAxis = 0;
    /** Virtual pixel position of the dragged band (jump-free fine / constrain math). */
    private double eqDragVirtX, eqDragVirtY, eqDragLastX, eqDragLastY;

    /** Modules in chain (signal-flow) order, mirrored by the chain tab bar. */
    private final List<ChainModule> chainModules = new ArrayList<>();
    /** The module whose panel is currently shown. */
    private ChainModule selectedModule;
    /** UI cards for the four dynamics compressors (for live -GR metering). */
    private final List<DynCard> dynCards = new ArrayList<>();
    /** Redraws the EQ during playback so dynamic-band handles animate. */
    private AnimationTimer eqAnimator;
    /** Live loudness meter - touched only on the FX thread (fed by drained buffers). */
    private final LoudnessMeter liveMeter = new LoudnessMeter();
    /** Processed output buffers handed off from the audio thread for live metering. */
    private final java.util.concurrent.ConcurrentLinkedQueue<float[]> meterQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile double livePeak = 0.0;

    /**
     * Standard MP3 bitrate options offered in the export dialog
     * (kbps). The list spans the typical range from compact (64)
     * to highest standard (320).
     */
    private static final List<Integer> MP3_BITRATE_OPTIONS =
            Arrays.asList(64, 96, 128, 160, 192, 224, 256, 320);

    /* =========================================================
     *  Initialisation
     * ========================================================= */

    /**
     * Called automatically by JavaFX after the FXML scene is
     * loaded and all {@code @FXML} fields are injected. Wires:
     * <ul>
     *   <li>slider listeners that push values into processors,</li>
     *   <li>player property listeners that update transport UI,</li>
     *   <li>mouse handlers on the waveform canvas (click + drag),</li>
     *   <li>wrapper-size listeners that resize the Canvas with the
     *       parent so the waveform stays full-width when the window
     *       is resized,</li>
     *   <li>initial disabled state for transport buttons until a
     *       file is loaded,</li>
     *   <li>audio-style keyboard and mouse shortcuts on every
     *       slider (coarse / Shift / Ctrl-Cmd step sizes plus
     *       reset gestures, applied uniformly via the wheel,
     *       arrow keys, and click-and-drag).</li>
     * </ul>
     */
    @FXML
    public void initialize()
    {
        AppLogger.info("Controller initialised.");

        initEqUi();
        initAutoEqUi();
        initLimiterUi();
        initChainUi();
        initDynamicsUi();
        initClipUi();
        initFileDragAndDrop();

        // --- Fade: always on, edited by dragging the handles on the waveform ---
        fade.setEnabled(true);

        // --- Peak Normalizer enable (its target is the peakTarget slider, right panel) ---
        peakEnabled.selectedProperty().addListener((obs, o, n) ->
        {
            normalizer.setEnabled(n);
            peakAppliedLabel.setText(n ? formatSignedDb(normalizer.getGainDb()) : "·");
        });
        normalizer.setEnabled(peakEnabled.isSelected());   // transparent by default (off)

        // Trim is done by selecting a range on the waveform (shift-drag) and
        // pressing Crop / Delete - see the waveform mouse handlers.

        // --- Player → UI bindings ---
        player.playingProperty().addListener((obs, o, n) ->
                playButton.setText(n ? "⏸ Pause" : "▶ Play"));

        // Live metering tap: the audio thread hands processed buffers off to a
        // bounded queue; all metering (LUFS, true peak, stereo image) is
        // computed on the FX thread when the queue is drained.
        player.setMeterTap((buf, ch) ->
        {
            if (meterQueue.size() < 32)
                meterQueue.add(java.util.Arrays.copyOf(buf, buf.length));
        });

        player.positionSamplesProperty().addListener((obs, o, n) ->
        {
            updatePositionLabel();
            drawWaveform();
        });

        player.abModeProperty().addListener((obs, o, n) ->
                abButton.setSelected(n));

        // The two A/B controls answer different questions; say so on hover.
        abButton.setTooltip(new Tooltip(
                "Compare the processed master with the ORIGINAL audio (time-aligned bypass)."));
        if (settingsAbButton != null)
        {
            settingsAbButton.setTooltip(new Tooltip(
                    "Two complete chain setups (A and B): toggling stores the current settings"
                            + " into the active slot and applies the other one. Use it to compare"
                            + " two different masters of the same song."));
        }

        // --- Mouse handlers on waveform: click + drag scrubbing ---
        waveformCanvas.addEventHandler(MouseEvent.MOUSE_PRESSED,  this::onWaveformMousePressed);
        waveformCanvas.addEventHandler(MouseEvent.MOUSE_DRAGGED,  this::onWaveformMouseDragged);
        waveformCanvas.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onWaveformMouseReleased);
        waveformCanvas.addEventHandler(ScrollEvent.SCROLL,        this::onWaveformScroll);

        // Keyboard: Ctrl/⌘+Z undo, Ctrl/⌘+Shift+Z redo (registered once the scene exists).
        eqCanvas.sceneProperty().addListener((o, oldS, newS) ->
        {
            if (newS != null)
            {
                newS.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Z"), this::onUndo);
                newS.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Shift+Z"), this::onRedo);
                // Space toggles play/pause anywhere - except while typing in a field.
                newS.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev ->
                {
                    if (exporting) return;   // window is locked during export
                    if (ev.getCode() == javafx.scene.input.KeyCode.SPACE
                            && !(newS.getFocusOwner() instanceof javafx.scene.control.TextInputControl))
                    {
                        onPlayPause();
                        ev.consume();
                    }
                });
            }
        });
        updateUndoRedoButtons();

        // Oversampling: a toggle + factor selector (right panel). Drives the live
        // engine (player) and the offline render (measure / export). The factor
        // selector is greyed out while oversampling is off.
        osCombo.getItems().setAll("2x", "4x", "8x", "16x");
        osCombo.getSelectionModel().select(1);          // 4x default
        osCombo.disableProperty().bind(osToggle.selectedProperty().not());
        osToggle.selectedProperty().addListener((o, ov, nv) ->
        {
            osToggle.setText(nv ? "on" : "off");
            updateOversampling();
        });
        osCombo.valueProperty().addListener((o, ov, nv) -> updateOversampling());
        updateOversampling();

        // --- Canvas follows wrapper size ---
        waveformWrapper.widthProperty().addListener((obs, o, n) ->
        {
            waveformCanvas.setWidth(n.doubleValue());
            if (loadedFile != null)
            {
                downsampleForDisplay();
            }
            drawWaveform();
        });
        waveformWrapper.heightProperty().addListener((obs, o, n) ->
        {
            waveformCanvas.setHeight(n.doubleValue());
            drawWaveform();
        });

        // --- Disable transport until a file is loaded ---
        playButton.setDisable(true);
        stopButton.setDisable(true);
        abButton.setDisable(true);
        goStartButton.setDisable(true);
        goEndButton.setDisable(true);

        setStatus("Ready. Load or drop a file to begin.");

        // Initial paint of the empty waveform area.
        Platform.runLater(this::drawWaveform);
    }

    /**
     * Constructs the processing pipeline:
     * <pre>
     *   Equalizer → Fade → Peak Normalizer → Limiter
     * </pre>
     * The unified {@link EqualizerProcessor} is the first block; with
     * per-band channel routing and flat GAIN bands it does all tone work
     * plus L/R volume, Mid/Side and stereo width itself (there are no
     * separate modules for those). The chain ends with peak normalization -
     * which analyses the signal <i>after</i> all upstream processing, the
     * EQ included - followed by a true-peak limiter as the final safety net.
     *
     * @return  the fully configured pipeline
     */
    private ProcessingPipeline buildPipeline()
    {
        ProcessingPipeline p = new ProcessingPipeline();
        p.addProcessor(autoEq);        // EQ block: Auto EQ → EQ → Fade
        p.addProcessor(eq);
        p.addProcessor(fade);
        p.addProcessor(peakComp);      // Dynamics: the four automatic compressors
        p.addProcessor(beatComp);
        p.addProcessor(leveler);
        p.addProcessor(punch);
        p.addProcessor(softClip);      // Clip: Soft-Clip (saturation) → Hard-Clip
        p.addProcessor(hardClip);
        p.addProcessor(multiband);     // Limit: multiband then broadband true-peak limiting
        p.addProcessor(broadband);
        p.addProcessor(normalizer);    // Peak Normalizer (final true-peak output)
        return p;
    }

    /* =========================================================
     *  Audio-style shortcuts on sliders
     * ========================================================= */

    /**
     * Installs the full set of audio-style keyboard and mouse
     * shortcuts on the given slider. See the class-level
     * Javadoc for the gesture table.
     * <p>
     * Cross-platform modifier detection uses
     * {@code isShortcutDown()}, which JavaFX maps to
     * {@code Cmd} on macOS and {@code Ctrl} on Windows/Linux
     * automatically. No explicit operating-system check is
     * therefore needed.
     * <p>
     * Each step application rounds the result to two decimal
     * places to prevent floating-point drift from accumulating
     * across repeated wheel or arrow events (so the value
     * remains exact integer-dB after a series of Shift-wheel
     * clicks, instead of slowly drifting into tail-end digits).
     *
     * @param slider        the slider to enhance
     * @param coarseStep    step size with no modifier
     * @param standardStep  step size when Shift is held
     * @param fineStep      step size when Ctrl/Cmd is held
     * @param defaultValue  value the slider returns to when the
     *                      user double-clicks or Ctrl/Cmd-clicks
     */
    private static void installAudioSliderShortcuts(
            Slider slider,
            double coarseStep,
            double standardStep,
            double fineStep,
            double defaultValue)
    {
        installWheelShortcut (slider, coarseStep, standardStep, fineStep);
        installArrowShortcut (slider, coarseStep, standardStep, fineStep);
        installDragShortcut  (slider, standardStep, fineStep);
        installResetShortcut (slider, defaultValue);
    }

    /**
     * Wires up mouse-wheel adjustment with modifier-dependent
     * step size. Falls back to {@code deltaX} when
     * {@code deltaY} is zero, which happens on systems that
     * convert Shift+wheel-vertical into wheel-horizontal at the
     * OS or driver level (macOS, some Windows mouse drivers,
     * Wayland). Without this fallback, Shift+wheel would either
     * always increase the value or appear to do nothing.
     */
    private static void installWheelShortcut(
            Slider slider,
            double coarseStep,
            double standardStep,
            double fineStep)
    {
        slider.addEventFilter(ScrollEvent.SCROLL, event ->
        {
            if (slider.isDisabled()) return;

            double step = chooseStep(
                    event.isShortcutDown(), event.isShiftDown(),
                    coarseStep, standardStep, fineStep);

            // Prefer vertical scroll; if the OS has converted it
            // to horizontal (Shift+wheel on macOS and others),
            // use the horizontal delta with its natural sign.
            double delta = event.getDeltaY();
            if (delta == 0.0)
            {
                delta = event.getDeltaX();
            }
            if (delta == 0.0)
            {
                // No actionable scroll direction available.
                event.consume();
                return;
            }

            double direction = (delta > 0.0) ? +1.0 : -1.0;
            applyStep(slider, direction * step);
            event.consume();
        });
    }

    /**
     * Wires up arrow-key adjustment with modifier-dependent
     * step size. Uses an event <i>filter</i> rather than a
     * handler so the slider's default arrow-key behaviour is
     * pre-empted and our step sizes are the ones the user
     * sees, uniformly with the wheel.
     */
    private static void installArrowShortcut(
            Slider slider,
            double coarseStep,
            double standardStep,
            double fineStep)
    {
        slider.addEventFilter(KeyEvent.KEY_PRESSED, event ->
        {
            if (slider.isDisabled()) return;

            double step = chooseStep(
                    event.isShortcutDown(), event.isShiftDown(),
                    coarseStep, standardStep, fineStep);

            switch (event.getCode())
            {
                case UP:
                case RIGHT:
                    applyStep(slider, +step);
                    event.consume();
                    break;
                case DOWN:
                case LEFT:
                    applyStep(slider, -step);
                    event.consume();
                    break;
                default:
                    // Not our key - let the default handler run.
                    break;
            }
        });
    }

    /**
     * Wires up snap-to-step behaviour for Shift+drag and
     * Ctrl/Cmd+drag (excluding the Ctrl/Cmd single-click
     * reset, which is handled separately). With no modifier
     * the slider keeps its default continuous-drag behaviour;
     * with a modifier each drag event is mapped to the cursor
     * position and rounded to the nearest multiple of the
     * appropriate step.
     * <p>
     * Both {@link MouseEvent#MOUSE_PRESSED} and
     * {@link MouseEvent#MOUSE_DRAGGED} are intercepted via an
     * event filter, and consumed only when a modifier is held.
     * This keeps the default slider behaviour intact for
     * un-modified clicks and drags.
     */
    private static void installDragShortcut(
            Slider slider,
            double standardStep,
            double fineStep)
    {
        slider.addEventFilter(MouseEvent.MOUSE_PRESSED, event ->
        {
            if (slider.isDisabled()) return;
            if (!event.isShiftDown() && !event.isShortcutDown()) return;
            // Ctrl/Cmd single-click resets the value; the reset
            // handler will fire on the matching MOUSE_CLICKED.
            // We still consume PRESSED so the slider's default
            // "jump to click point" doesn't run, which would
            // flash the value to a near-cursor position before
            // the reset gesture lands.
            if (event.isShortcutDown())
            {
                event.consume();
                return;
            }
            snapDragToCursor(slider, event, standardStep);
            event.consume();
        });

        slider.addEventFilter(MouseEvent.MOUSE_DRAGGED, event ->
        {
            if (slider.isDisabled()) return;
            if (!event.isShiftDown() && !event.isShortcutDown()) return;

            double step = event.isShortcutDown() ? fineStep : standardStep;
            snapDragToCursor(slider, event, step);
            event.consume();
        });
    }

    /**
     * Wires up the two reset gestures recognised on every
     * slider: double-click without modifiers, and
     * Ctrl/Cmd + single click. Both jump the value back to the
     * parameter's default. The double-click gesture is the
     * lighter, more discoverable one; the Ctrl/Cmd single-click
     * is the precision shortcut commonly found in DAW
     * plug-ins.
     */
    private static void installResetShortcut(Slider slider, double defaultValue)
    {
        slider.addEventFilter(MouseEvent.MOUSE_CLICKED, event ->
        {
            if (slider.isDisabled()) return;

            boolean doubleClickNoMods =
                    event.getClickCount() == 2
                            && !event.isShiftDown()
                            && !event.isShortcutDown();
            boolean ctrlSingleClick =
                    event.getClickCount() == 1 && event.isShortcutDown();

            if (doubleClickNoMods || ctrlSingleClick)
            {
                slider.setValue(clampToRange(slider, defaultValue));
                event.consume();
            }
        });
    }

    /**
     * Maps the cursor position inside the slider's bounds to a
     * value on the slider's scale, rounds the value to the
     * nearest multiple of {@code step}, and applies it. Used
     * for both Shift+drag and Ctrl/Cmd+drag. Handles
     * horizontal and vertical slider orientations.
     */
    private static void snapDragToCursor(Slider slider, MouseEvent event, double step)
    {
        double width  = slider.getWidth();
        double height = slider.getHeight();
        if (width <= 0 || height <= 0) return;

        double pos;
        if (slider.getOrientation() == Orientation.HORIZONTAL)
        {
            pos = event.getX() / width;
        }
        else
        {
            // Vertical sliders: top is the max, bottom is the
            // min, so we invert the Y fraction.
            pos = 1.0 - event.getY() / height;
        }
        if (pos < 0.0) pos = 0.0;
        if (pos > 1.0) pos = 1.0;

        double range = slider.getMax() - slider.getMin();
        double rawValue = slider.getMin() + pos * range;

        // Snap to the nearest multiple of step (relative to the
        // slider's minimum, so a min of -24 with a step of 1
        // still lands on integer dB).
        double snapped = slider.getMin()
                + Math.round((rawValue - slider.getMin()) / step) * step;
        snapped = clampToRange(slider, snapped);
        snapped = Math.round(snapped * 100.0) / 100.0;
        slider.setValue(snapped);
    }

    /**
     * Picks the step size that matches the modifier state. The
     * priority is: Ctrl/Cmd → fine, then Shift → standard,
     * otherwise coarse. The shortcut wins over Shift when both
     * are held; this matches how nudging with two modifiers
     * behaves in most DAWs (the finest available step takes
     * precedence).
     */
    private static double chooseStep(
            boolean shortcut, boolean shift,
            double coarseStep, double standardStep, double fineStep)
    {
        if (shortcut) return fineStep;
        if (shift)    return standardStep;
        return coarseStep;
    }

    /**
     * Applies a signed delta to the slider's value, clamping
     * the result to the slider's [min, max] range and rounding
     * to two decimal places to avoid floating-point drift.
     */
    private static void applyStep(Slider slider, double delta)
    {
        double raw = slider.getValue() + delta;
        double clamped = clampToRange(slider, raw);
        double rounded = Math.round(clamped * 100.0) / 100.0;
        slider.setValue(rounded);
    }

    /**
     * Returns {@code value} clamped to the inclusive range
     * {@code [slider.getMin(), slider.getMax()]}.
     */
    private static double clampToRange(Slider slider, double value)
    {
        if (value < slider.getMin()) return slider.getMin();
        if (value > slider.getMax()) return slider.getMax();
        return value;
    }

    /* =========================================================
     *  Event handlers (called from FXML onAction)
     * ========================================================= */

    /**
     * Opens a file chooser dialog and loads the selected audio
     * file on a background task. On success the UI is updated
     * to reflect the new file (file info, waveform, default
     * sliders, automatic peak analysis and LUFS measurement);
     * on failure an error dialog is shown.
     */
    @FXML
    private void onLoadFile()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load audio file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio files", "*.wav", "*.mp3"),
                new FileChooser.ExtensionFilter("All files",   "*.*"));

        String defaultDir = config.getOutputDir();
        File initial = new File(defaultDir);
        if (initial.isDirectory())
        {
            chooser.setInitialDirectory(initial);
        }

        Window window = waveformCanvas.getScene().getWindow();
        File chosen = chooser.showOpenDialog(window);
        if (chosen == null)
        {
            return;
        }

        loadAudioFile(chosen);
    }

    /**
     * Loads an audio file on a background thread and, on success,
     * hands off to {@link #onAudioReady} to configure the whole UI
     * for it. Shared by the "Load file" button and by drag-and-drop.
     * The format is detected from the file content, not its
     * extension; load failures are logged and shown in a dialog.
     */
    private void loadAudioFile(File file)
    {
        final String path = file.getAbsolutePath();
        setStatus("Loading " + file.getName() + " …");

        Task<AudioFile> task = new Task<>()
        {
            @Override
            protected AudioFile call() throws AudioFileException
            {
                AudioFile loaded = AudioFormatDetector.loadAuto(path);
                loaded.load();
                return loaded;
            }
        };

        task.setOnSucceeded(ev ->
        {
            loadedFile = task.getValue();
            onAudioReady(file);
        });
        task.setOnFailed(ev ->
        {
            Throwable cause = task.getException();
            AppLogger.error("Failed to load " + path, cause);
            setStatus("Load failed.");
            showError("Could not load the file", cause.getMessage());
        });
        runTask(task);
    }

    /**
     * Wires whole-window drag-and-drop loading. Filters on the root
     * run in the capture phase, so they see the gesture before the
     * chain chips (which only ever accept their own string payload
     * for reordering) and work over the entire window. A drop is
     * accepted only when it carries a file with a supported audio
     * extension; the window is highlighted while such a file hovers.
     */
    private void initFileDragAndDrop()
    {
        rootPane.addEventFilter(DragEvent.DRAG_OVER, ev ->
        {
            Dragboard db = ev.getDragboard();
            if (db.hasFiles() && firstAudioFile(db.getFiles()) != null)
            {
                ev.acceptTransferModes(TransferMode.COPY);
                if (!fileDragActive)
                {
                    fileDragActive = true;
                    rootPane.pseudoClassStateChanged(FILE_DRAG_PSEUDO, true);
                }
                ev.consume();
            }
        });

        rootPane.addEventFilter(DragEvent.DRAG_EXITED, ev -> clearFileDragHighlight());

        rootPane.addEventFilter(DragEvent.DRAG_DROPPED, ev ->
        {
            Dragboard db = ev.getDragboard();
            boolean loaded = false;
            if (db.hasFiles())
            {
                File audio = firstAudioFile(db.getFiles());
                if (audio != null)
                {
                    loadAudioFile(audio);
                    loaded = true;
                }
            }
            clearFileDragHighlight();
            ev.setDropCompleted(loaded);
            ev.consume();
        });
    }

    /** Removes the file-drag highlight if it is currently showing. */
    private void clearFileDragHighlight()
    {
        if (fileDragActive)
        {
            fileDragActive = false;
            rootPane.pseudoClassStateChanged(FILE_DRAG_PSEUDO, false);
        }
    }

    /**
     * Returns the first regular file in the list whose name ends in
     * a supported audio extension (.wav / .mp3), or {@code null} if
     * none qualifies. This is a quick check to decide whether to
     * accept a drag; the real format is verified by content when the
     * file is loaded.
     */
    private static File firstAudioFile(List<File> files)
    {
        for (File f : files)
        {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if ((name.endsWith(".wav") || name.endsWith(".mp3")) && f.isFile())
            {
                return f;
            }
        }
        return null;
    }

    /**
     * Discards all current edits: restores the file's samples to
     * the original, resets trim sliders to zero, re-prepares the
     * player, recomputes peak and LUFS, and redraws the
     * waveform. The processor sliders are left at their current
     * positions on purpose - the user may still want those
     * settings.
     */
    @FXML
    private void onReset()
    {
        if (loadedFile == null) return;
        loadedFile.reset();
        clearSelectionState();
        clearHistory();
        player.prepare(loadedFile);
        downsampleForDisplay();
        drawWaveform();
        measureInputPeak();
        recomputeTrackAnalysis(this::measureOutput);
        setStatus("All edits discarded.");
        AppLogger.info("All edits discarded.");
    }

    /**
     * Toggles between play and pause. The first play after a
     * load also applies trim and re-prepares the player to make
     * sure playback starts from a fresh state with the current
     * edits.
     */
    @FXML
    private void onPlayPause()
    {
        if (loadedFile == null) return;

        if (player.isPlaying())
        {
            player.pause();
            setStatus("Paused.");
        }
        else
        {
            player.play();
            setStatus("Playing.");
        }
    }

    /**
     * Stops playback and resets the play position to the start.
     */
    @FXML
    private void onStop()
    {
        player.stop();
        setStatus("Stopped.");
    }

    /**
     * Jumps the play position to the start of the audio. Works
     * during playback (audio resumes from the start), while
     * paused, or while stopped.
     */
    @FXML
    private void onGoToStart()
    {
        if (loadedFile == null) return;
        player.seekTo(0.0);
        setStatus("At start.");
    }

    /**
     * Jumps the play position to just before the end of the
     * audio. If the player is playing, playback will naturally
     * stop a moment later when the loop reaches end-of-stream.
     */
    @FXML
    private void onGoToEnd()
    {
        if (loadedFile == null) return;
        double end = Math.max(0.0, loadedFile.getDuration() - 0.001);
        player.seekTo(end);
        setStatus("At end.");
    }

    /**
     * Toggles A/B comparison: alternates between processed audio
     * and the original. Effective immediately, even during
     * playback.
     */
    @FXML
    private void onToggleAB()
    {
        if (loadedFile == null) return;
        player.toggleAB();
        setStatus(player.isAbMode() ? "A/B: original (bypass)" : "A/B: processed");
    }

    /**
     * User-triggered peak re-analysis. Delegates to the shared
     * helper so the same logic is used both manually and after
     * load/reset.
     */
    @FXML
    private void onReanalyzePeak()
    {
        if (loadedFile == null) return;
        runPeakAnalysis();
    }

    /**
     * User-triggered LUFS measurement. Delegates to the shared
     * helper.
     */
    @FXML
    private void onMeasureLufs()
    {
        if (loadedFile == null) return;
        runLufsMeasurement();
    }

    /**
     * Exports the processed audio to a user-chosen file. The
     * user picks the output format (WAV or MP3) by selecting
     * the corresponding extension in the file chooser. When MP3
     * is chosen, a secondary dialog asks for the encoding
     * bitrate; the default is the bitrate of the loaded MP3 (if
     * the input was MP3) or 320 kbps (if the input was WAV).
     * <p>
     * Export runs on a background task: reset the file to the
     * original samples, apply trim if needed, run the full
     * pipeline on the editable buffer, build the appropriate
     * {@link AudioFile} subclass for the target format, and
     * save to disk.
     */
    @FXML
    private void onExport()
    {
        if (loadedFile == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export processed audio");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("WAV (lossless)", "*.wav"),
                new FileChooser.ExtensionFilter("MP3 (lossy)",    "*.mp3"));
        chooser.setInitialFileName(suggestedOutputName());

        String defaultDir = config.getOutputDir();
        File initial = new File(defaultDir);
        if (initial.isDirectory()) chooser.setInitialDirectory(initial);

        Window window = waveformCanvas.getScene().getWindow();
        File target = chooser.showSaveDialog(window);
        if (target == null) return;

        final String path = target.getAbsolutePath();
        final boolean exportAsMp3 = path.toLowerCase(Locale.US).endsWith(".mp3");

        // Ask for the encoding settings (sample rate + bit depth / bitrate),
        // defaulting to the loaded file's own format.
        int fileBits = 24;
        boolean fileFloat = false;
        if (loadedFile instanceof WavFile wav) { fileBits = wav.getBitDepth(); fileFloat = wav.isFloat(); }
        final ExportSettings settings = askExportSettings(
                exportAsMp3, loadedFile.getSampleRate(), fileBits, fileFloat);
        if (settings == null) { setStatus("Export cancelled."); return; }

        // Stop live playback before exporting: the audio thread and the offline
        // render must never touch the chain at once. The render also runs on an
        // independent snapshot of the chain (never the live pipeline), so the two
        // stay fully decoupled even if the audio thread is still winding down.
        player.stop();
        final Snapshot snap = buildSnapshot();

        final int srcRate = loadedFile.getSampleRate();
        final int ch = loadedFile.getChannels();
        final float[] src = loadedFile.getSamples().clone();   // the (possibly trimmed) editable audio
        final int os = oversampling;

        Task<Void> task = new Task<>()
        {
            @Override
            protected Void call() throws AudioFileException
            {
                // Render the snapshot chain on a throwaway copy (the editable audio
                // is untouched), oversampled if requested. Streams block-by-block
                // (bounded memory) and reports progress; a Cancel throws out of the
                // progress callback at the next block boundary (snapshot discarded).
                float[] processed = renderOversampled(snap.pipeline, src, srcRate, ch, os,
                        frac ->
                        {
                            if (isCancelled())
                                throw new java.util.concurrent.CancellationException();
                            updateProgress(frac, 1.0);
                        });
                if (isCancelled()) return null;   // cancelled: write nothing
                float[] out = resampleForExport(processed, ch, srcRate, settings.sampleRate());
                if (settings.sampleRate() != srcRate && snap.normalizer.isEnabled())
                {
                    reclampTruePeak(out, ch, snap.normalizer.getTargetDbfs());
                }
                if (isCancelled()) return null;
                if (exportAsMp3)
                {
                    new Mp3File(path, settings.sampleRate(), ch, out, settings.kbps(), false).save(path);
                }
                else
                {
                    new WavFile(path, settings.sampleRate(), ch, out,
                            settings.bitDepth(), settings.isFloat()).save(path);
                }
                // Same-container exports keep the source's tags (ID3 / LIST-INFO / bext).
                MetadataPreserver.preserve(loadedFile.getFilePath(), path);
                return null;
            }
        };

        task.setOnSucceeded(ev ->
        {
            hideExportOverlay();
            setStatus("Exported: " + target.getName());
            AppLogger.info("Exported to: " + path);
            File parent = target.getParentFile();
            if (parent != null) config.setOutputDir(parent.getAbsolutePath());
        });
        task.setOnFailed(ev ->
        {
            hideExportOverlay();
            AppLogger.error("Export failed: " + path, task.getException());
            setStatus("Export failed.");
            showError("Export failed", task.getException().getMessage());
        });
        task.setOnCancelled(ev ->
        {
            hideExportOverlay();
            setStatus("Export cancelled.");
            AppLogger.info("Export cancelled: " + path);
        });
        exportTask = task;
        showExportOverlay(target.getName(), task);
        runTask(task);
    }

    /**
     * Cancel action from the export overlay. Requests cancellation of the running
     * export task; the task notices at its next block boundary and its
     * onCancelled handler restores the window.
     */
    @FXML
    private void onCancelExport()
    {
        if (exportTask != null) exportTask.cancel(true);
    }

    /**
     * Shows the modal export overlay and locks the rest of the window. Binds the
     * overlay's progress bar to the task and listens to its progress to keep the
     * percentage and elapsed / remaining labels current. Disabling {@code rootPane}
     * blocks every control beneath the overlay (and file drag-and-drop); the
     * overlay is a sibling in the StackPane, so its Cancel button stays live.
     */
    private void showExportOverlay(String fileName, Task<?> task)
    {
        exporting = true;
        exportStartNanos = System.nanoTime();
        exportFileLabel.setText(fileName);
        exportPercentLabel.setText("Preparing…");
        exportTimeLabel.setText("");
        exportBar.progressProperty().bind(task.progressProperty());
        task.progressProperty().addListener((o, ov, nv) -> updateExportEta(nv.doubleValue()));
        exportOverlay.setVisible(true);
        exportOverlay.setManaged(true);
        rootPane.setDisable(true);
        setStatus("Exporting …");
    }

    /** Hides the export overlay, unlocks the window and clears export state. */
    private void hideExportOverlay()
    {
        exporting = false;
        exportTask = null;
        exportBar.progressProperty().unbind();
        exportBar.setProgress(0.0);
        exportOverlay.setVisible(false);
        exportOverlay.setManaged(false);
        rootPane.setDisable(false);
    }

    /** Updates the overlay's percentage and remaining-time labels from progress in [0, 1]. */
    private void updateExportEta(double p)
    {
        if (p <= 0.0)
        {
            exportPercentLabel.setText("Preparing…");
            exportTimeLabel.setText("");
            return;
        }
        exportPercentLabel.setText(String.format(Locale.US, "%.0f %%", p * 100.0));
        double elapsed = (System.nanoTime() - exportStartNanos) / 1e9;
        double remaining = elapsed * (1.0 - p) / p;
        exportTimeLabel.setText(formatClock(elapsed) + " elapsed · " + formatClock(remaining) + " left");
    }

    /** Formats a duration in seconds as m:ss (zero-clamped, non-finite-safe). */
    private static String formatClock(double seconds)
    {
        if (seconds < 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) seconds = 0;
        int total = (int) Math.round(seconds);
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    /** Shows/hides the "Analyzing audio" indicator (reference-counted across jobs). */
    private void analyzing(boolean on)
    {
        analyzeJobs = Math.max(0, analyzeJobs + (on ? 1 : -1));
        boolean show = analyzeJobs > 0;
        if (analyzeLabel != null) { analyzeLabel.setVisible(show); analyzeLabel.setManaged(show); }
        if (analyzeProgress != null) { analyzeProgress.setVisible(show); analyzeProgress.setManaged(show); }
    }

    /** Encoding settings chosen in the export dialog. */
    private record ExportSettings(int sampleRate, int bitDepth, boolean isFloat, int kbps) { }

    /**
     * Asks for the export encoding: sample rate (defaulting to the file's) and
     * either bit depth (WAV) or bitrate (MP3, up to 320 kbps).
     */
    private ExportSettings askExportSettings(boolean isMp3, int fileRate, int fileBits, boolean fileFloat)
    {
        ComboBox<Integer> srCombo = new ComboBox<>();
        srCombo.getItems().addAll(44100, 48000, 88200, 96000, 176400, 192000);
        if (!srCombo.getItems().contains(fileRate)) srCombo.getItems().add(0, fileRate);
        srCombo.setValue(fileRate);

        ComboBox<String> bitCombo = new ComboBox<>();
        ComboBox<Integer> kbpsCombo = new ComboBox<>();

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.addRow(0, new Label("Sample rate (Hz)"), srCombo);
        if (isMp3)
        {
            kbpsCombo.getItems().addAll(96, 128, 160, 192, 224, 256, 320);
            kbpsCombo.setValue(320);
            g.addRow(1, new Label("Bitrate (kbps)"), kbpsCombo);
        }
        else
        {
            bitCombo.getItems().addAll("16-bit", "24-bit", "32-bit float");
            bitCombo.setValue(fileFloat ? "32-bit float"
                    : (fileBits == 16 ? "16-bit" : (fileBits == 32 ? "32-bit float" : "24-bit")));
            g.addRow(1, new Label("Bit depth"), bitCombo);
        }

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Export settings");
        dlg.setHeaderText(isMp3 ? "MP3 encoding" : "WAV encoding");
        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return null;

        int sr = srCombo.getValue();
        if (isMp3) return new ExportSettings(sr, 16, false, kbpsCombo.getValue());
        String bd = bitCombo.getValue();
        if (bd.startsWith("16")) return new ExportSettings(sr, 16, false, 0);
        if (bd.startsWith("24")) return new ExportSettings(sr, 24, false, 0);
        return new ExportSettings(sr, 32, true, 0);
    }

    /** Applies the oversampling toggle + factor to the live player and the
     *  offline render (measure / export). Factor 1 when the toggle is off. */
    private void updateOversampling()
    {
        int factor = 1;
        if (osToggle != null && osToggle.isSelected())
        {
            String v = osCombo.getValue();
            factor = switch (v == null ? "" : v)
            {
                case "2x"  -> 2;
                case "8x"  -> 8;
                case "16x" -> 16;
                default    -> 4;
            };
        }
        oversampling = factor;
        if (player != null) player.setOversampling(factor);
    }

    /**
     * Renders the chain on a copy through a {@link WavFile}, optionally
     * oversampled. Delegates to {@link ProcessingPipeline#processOversampled},
     * which streams the file one block at a time (bounded memory) rather than
     * materializing the whole {@code factor ×}-rate signal - the latter
     * exhausted the heap on real songs at 4×/8×/16× ("Export failed: Java heap
     * space"). {@code progress} (may be {@code null}) is forwarded for a UI bar.
     */
    private static float[] renderOversampled(ProcessingPipeline p, float[] src, int sr, int ch,
                                             int factor, DoubleConsumer progress)
    {
        WavFile w = new WavFile("os", sr, ch, src, 16, false);
        p.processOversampled(w, factor, progress);
        return w.getSamples();
    }

    /**
     * Sample-rate conversion for export: a polyphase Kaiser windowed-sinc
     * converter (anti-aliased on downsampling), at the engine's highest
     * quality since export is offline. Returns the input unchanged when the
     * rates match (the common case).
     */
    private static float[] resampleForExport(float[] in, int channels, int fromRate, int toRate)
    {
        if (fromRate == toRate || in.length == 0) return in;
        com.dspark.core.Resampler resampler = new com.dspark.core.Resampler();
        resampler.prepare(fromRate, toRate, com.dspark.core.Resampler.Quality.ULTRA);
        return resampler.resampleInterleaved(in, channels);
    }

    /**
     * Re-anchors the delivery true peak after a sample-rate conversion: the
     * conversion moves the inter-sample peaks, so the ceiling the normalizer
     * guaranteed at the source rate is re-measured at the delivery rate and
     * the buffer is scaled down if it overshoots. Never scales up.
     */
    private static void reclampTruePeak(float[] out, int channels, double targetDbtp)
    {
        double tp = com.dspark.analysis.TruePeak.measureMax(out, channels);
        double targetLin = Math.pow(10.0, targetDbtp / 20.0);
        if (tp > targetLin && tp > 1e-9)
        {
            float g = (float) (targetLin / tp);
            for (int i = 0; i < out.length; i++) out[i] *= g;
        }
    }

    /**
     * Suggests an output filename based on the loaded file's
     * name. If no file is loaded, returns a generic default.
     */
    private String suggestedOutputName()
    {
        if (loadedFile == null) return "output.wav";
        String src = new File(loadedFile.getFilePath()).getName();
        int dot = src.lastIndexOf('.');
        String base = (dot > 0) ? src.substring(0, dot) : src;
        return base + "-mastered.wav";
    }

    /**
     * Shows a modal dialog asking the user to pick an MP3
     * encoding bitrate from the standard options. The default
     * is the loaded MP3's bitrate when the input was MP3, or
     * {@code Mp3File.DEFAULT_BITRATE_KBPS} (320 kbps) when the
     * input was anything else.
     *
     * @return  the chosen bitrate in kbps, or {@code null} if
     *          the user cancelled the dialog
     */
    private Integer askForMp3Bitrate()
    {
        int defaultBitrate = Mp3File.DEFAULT_BITRATE_KBPS;
        if (loadedFile instanceof Mp3File mp3)
        {
            defaultBitrate = pickClosestSupportedBitrate(mp3.getBitrate());
        }

        ChoiceDialog<Integer> dialog =
                new ChoiceDialog<>(defaultBitrate, MP3_BITRATE_OPTIONS);
        dialog.setTitle("MP3 Bitrate");
        dialog.setHeaderText("Select MP3 encoding bitrate");
        dialog.setContentText("Bitrate (kbps):");

        Optional<Integer> result = dialog.showAndWait();
        return result.orElse(null);
    }

    /**
     * Picks the bitrate option from {@link #MP3_BITRATE_OPTIONS}
     * closest to the given value, so the dialog opens on a
     * sensible default even when the source MP3's bitrate is
     * non-standard (which can happen with VBR files).
     */
    private static int pickClosestSupportedBitrate(int desired)
    {
        int best = MP3_BITRATE_OPTIONS.get(0);
        int bestDiff = Math.abs(best - desired);
        for (int option : MP3_BITRATE_OPTIONS)
        {
            int diff = Math.abs(option - desired);
            if (diff < bestDiff)
            {
                bestDiff = diff;
                best = option;
            }
        }
        return best;
    }

    /* =========================================================
     *  Waveform mouse handlers (click + drag scrubbing)
     * ========================================================= */

    /**
     * Mouse pressed on the waveform: start scrubbing mode and
     * seek to the click position. The user can then drag to
     * continue scrubbing without releasing the button.
     */
    private void onWaveformMousePressed(MouseEvent e)
    {
        if (loadedFile == null) return;

        // Shift-drag selects a range (for Crop / Delete).
        if (e.isShiftDown())
        {
            selStartSec = secAtX(e.getX());
            selEndSec = selStartSec;
            selecting = true;
            drawWaveform();
            return;
        }

        // Grab a fade handle if the press lands on one (top strip of the wave).
        double w = waveformCanvas.getWidth();
        double dur = loadedFile.getDuration();
        if (fade.isEnabled() && dur > 0.0 && e.getY() <= 22.0)
        {
            double inX  = (fade.getFadeInSec() / dur) * w;
            double outX = ((dur - fade.getFadeOutSec()) / dur) * w;
            if (Math.abs(e.getX() - inX) <= 14.0)
            {
                if (e.getClickCount() == 2) setFadeIn(0.0); else fadeDragMode = 1;
                return;
            }
            if (Math.abs(e.getX() - outX) <= 14.0)
            {
                if (e.getClickCount() == 2) setFadeOut(0.0); else fadeDragMode = 2;
                return;
            }
        }
        scrubbing = true;
        seekFromMouseX(e.getX());
    }

    /**
     * Mouse dragged while pressed: drag a fade handle (sets the fade length
     * directly on the waveform), or scrub the playback position.
     */
    private void onWaveformMouseDragged(MouseEvent e)
    {
        if (loadedFile == null) return;
        if (selecting)
        {
            selEndSec = secAtX(e.getX());
            drawWaveform();
            return;
        }
        double w = waveformCanvas.getWidth();
        double dur = loadedFile.getDuration();
        double xSec = (w > 0.0) ? Math.max(0.0, Math.min(dur, (e.getX() / w) * dur)) : 0.0;
        if (fadeDragMode == 1)      { setFadeIn(xSec);            return; }
        if (fadeDragMode == 2)      { setFadeOut(dur - xSec);     return; }
        if (scrubbing)              seekFromMouseX(e.getX());
    }

    /** Mouse released: end scrubbing / fade-drag / selection. */
    private void onWaveformMouseReleased(MouseEvent e)
    {
        scrubbing = false;
        if (fadeDragMode != 0)
        {
            fadeDragMode = 0;
            scheduleDynamicsRefresh();   // the fade changed: downstream analysis is stale
        }
        if (selecting)
        {
            selecting = false;
            updateSelectionLabel();
            if (loopButton != null && loopButton.isSelected()) applyLoopFromSelection();
        }
    }

    /** Maps an X pixel on the waveform to a time in seconds (clamped). */
    private double secAtX(double x)
    {
        double w = waveformCanvas.getWidth();
        if (w <= 0.0 || loadedFile == null) return 0.0;
        double dur = loadedFile.getDuration();
        return Math.max(0.0, Math.min(dur, (x / w) * dur));
    }

    private void setFadeIn(double sec)
    {
        fade.setFadeInSec(sec);
        drawWaveform();
        setStatus("Fade in: " + formatSec(fade.getFadeInSec()));
    }

    private void setFadeOut(double sec)
    {
        fade.setFadeOutSec(sec);
        drawWaveform();
        setStatus("Fade out: " + formatSec(fade.getFadeOutSec()));
    }

    /** Mouse wheel over a fade handle cycles the fade curve type. */
    private void onWaveformScroll(ScrollEvent e)
    {
        if (loadedFile == null) return;
        double w = waveformCanvas.getWidth();
        double dur = loadedFile.getDuration();
        if (dur <= 0.0 || w <= 0.0) return;
        double inX  = (fade.getFadeInSec() / dur) * w;
        double outX = ((dur - fade.getFadeOutSec()) / dur) * w;
        boolean nearHandle = e.getY() <= 24.0
                && (Math.abs(e.getX() - inX) <= 18.0 || Math.abs(e.getX() - outX) <= 18.0);
        if (fadeDragMode != 0 || nearHandle)
        {
            FadeProcessor.FadeType t = fade.cycleFadeType();
            drawWaveform();
            setStatus("Fade curve: " + t.name().toLowerCase(Locale.US));
            e.consume();
        }
    }

    /**
     * Maps an X pixel coordinate on the waveform canvas to an
     * audio time in seconds, then seeks the player to that time.
     *
     * @param x  mouse X position in canvas pixels
     */
    private void seekFromMouseX(double x)
    {
        double w = waveformCanvas.getWidth();
        if (w <= 0) return;
        double frac = x / w;
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        double sec = frac * loadedFile.getDuration();
        player.seekTo(sec);
    }

    /* =========================================================
     *  After load: configure UI for the new file
     * ========================================================= */

    /**
     * Called when a load task finishes successfully. Updates
     * the file information labels, configures the trim sliders'
     * range to the file's duration, enables the transport
     * buttons, prepares the player, and triggers automatic peak
     * and LUFS analysis so the user has both values visible
     * from the start.
     */
    private void onAudioReady(File chosen)
    {
        AppLogger.info("Loaded: " + chosen.getAbsolutePath()
                + " (" + loadedFile.getSampleRate() + " Hz, "
                + loadedFile.getChannels() + " ch, "
                + loadedFile.getDuration() + " s)");

        fileNameLabel.setText(chosen.getName());

        String fmt;
        if (loadedFile instanceof WavFile wav)
        {
            fmt = "WAV " + wav.getBitDepth() + "-bit " + (wav.isFloat() ? "float" : "integer");
        }
        else if (loadedFile instanceof Mp3File mp3)
        {
            fmt = "MP3 " + mp3.getBitrate() + " kbps" + (mp3.isVbr() ? " VBR" : " CBR");
        }
        else
        {
            fmt = "audio";
        }

        fileInfoLabel.setText(String.format(Locale.US,
                "%s, %d Hz, %d ch, %.2f s",
                fmt, loadedFile.getSampleRate(), loadedFile.getChannels(),
                loadedFile.getDuration()));

        clearSelectionState();
        clearHistory();
        if (loopButton != null) loopButton.setSelected(false);
        liveTp = null;            // new source: fresh true-peak detectors
        resetGoniometer();

        playButton.setDisable(false);
        stopButton.setDisable(false);
        abButton.setDisable(false);
        goStartButton.setDisable(false);
        goEndButton.setDisable(false);

        player.prepare(loadedFile);
        downsampleForDisplay();
        drawWaveform();
        updatePositionLabel();

        // Show the raw input peak (no processing) and the output loudness so
        // the user sees the numbers right after load, without pressing a button.
        measureInputPeak();
        // New track: drop any manual BPM and (re)detect tempo + onsets, then
        // measure the output once the analysis is ready.
        trackAnalysis.clearManualBpm();
        recomputeTrackAnalysis(this::measureOutput);

        setStatus("Loaded.");
    }

    /* =========================================================
     *  Trim
     * ========================================================= */

    /* =========================================================
     *  Waveform range selection + trim (crop / delete)
     * ========================================================= */

    private boolean hasSelection()
    {
        return loadedFile != null && selStartSec >= 0.0 && selEndSec >= 0.0
                && Math.abs(selEndSec - selStartSec) > 0.01;
    }

    private void clearSelectionState()
    {
        selStartSec = -1.0;
        selEndSec = -1.0;
        selecting = false;
        if (selectionLabel != null) selectionLabel.setText("shift-drag the wave to select");
    }

    @FXML
    private void onClearSelection()
    {
        clearSelectionState();
        drawWaveform();
    }

    private void updateSelectionLabel()
    {
        if (!hasSelection())
        {
            selectionLabel.setText("shift-drag the wave to select");
            return;
        }
        double a = Math.min(selStartSec, selEndSec), b = Math.max(selStartSec, selEndSec);
        selectionLabel.setText(String.format(Locale.US, "%.2f - %.2f s", a, b));
    }

    /** Frame-aligned interleaved sample index for a time, clamped to the buffer. */
    private int sampleIndexAt(double sec)
    {
        int ch = loadedFile.getChannels();
        long frame = Math.round(sec * loadedFile.getSampleRate());
        long idx = frame * ch;
        int len = loadedFile.getSamples().length;
        if (idx < 0) idx = 0;
        if (idx > len) idx = len;
        return (int) (idx - (idx % ch));
    }

    /** Keeps only the selected range. */
    @FXML
    private void onCropSelection()
    {
        if (!hasSelection()) return;
        float[] s = loadedFile.getSamples();
        int i1 = sampleIndexAt(Math.min(selStartSec, selEndSec));
        int i2 = sampleIndexAt(Math.max(selStartSec, selEndSec));
        if (i2 <= i1) return;
        pushUndo();
        loadedFile.setSamples(java.util.Arrays.copyOfRange(s, i1, i2));
        afterTrim("Cropped to selection.");
    }

    /** Removes the selected range (splicing the two sides together). */
    @FXML
    private void onDeleteSelection()
    {
        if (!hasSelection()) return;
        float[] s = loadedFile.getSamples();
        int i1 = sampleIndexAt(Math.min(selStartSec, selEndSec));
        int i2 = sampleIndexAt(Math.max(selStartSec, selEndSec));
        if (i2 <= i1) return;
        pushUndo();
        float[] out = new float[s.length - (i2 - i1)];
        System.arraycopy(s, 0, out, 0, i1);
        System.arraycopy(s, i2, out, i1, s.length - i2);
        loadedFile.setSamples(out);
        afterTrim("Deleted selection.");
    }

    /** Re-prepares the player and redraws after the audio length changed. */
    private void afterTrim(String message)
    {
        clearSelectionState();
        // Clamp fades to the new (shorter) duration.
        double dur = loadedFile.getDuration();
        if (fade.getFadeInSec()  > dur) fade.setFadeInSec(dur);
        if (fade.getFadeOutSec() > dur) fade.setFadeOutSec(dur);
        player.stop();
        player.prepare(loadedFile);
        downsampleForDisplay();
        drawWaveform();
        measureInputPeak();
        // The timeline changed (crop / delete / undo): re-detect tempo + onsets
        // so Glue and Punch follow the new audio, then re-measure.
        recomputeTrackAnalysis(this::measureOutput);
        setStatus(message);
        AppLogger.info(message + " New duration: " + dur + "s");
    }

    /* --- Undo / redo: destructive audio edits AND parameter gestures --- */

    private void pushUndo()
    {
        if (loadedFile == null) return;
        undoStack.push(new EditState(loadedFile.getSamples().clone(), capturePreset()));
        trimUndoHistory();
        redoStack.clear();
        updateUndoRedoButtons();
    }

    /** Bounds the history by step count AND total bytes (sample clones are big). */
    private void trimUndoHistory()
    {
        while (undoStack.size() > MAX_UNDO_STEPS) undoStack.removeLast();
        long total = 0;
        for (EditState s : undoStack) total += s.bytes();
        while (total > MAX_UNDO_BYTES && undoStack.size() > 1)
        {
            total -= undoStack.removeLast().bytes();
        }
    }

    private void clearHistory()
    {
        undoStack.clear();
        redoStack.clear();
        paramGestureBaseline = null;
        updateUndoRedoButtons();
    }

    private void updateUndoRedoButtons()
    {
        if (undoButton != null) undoButton.setDisable(undoStack.isEmpty());
        if (redoButton != null) redoButton.setDisable(redoStack.isEmpty());
    }

    @FXML
    private void onUndo()
    {
        if (exporting) return;
        if (loadedFile == null || undoStack.isEmpty()) return;
        EditState prev = undoStack.pop();
        redoStack.push(new EditState(
                (prev.samples != null) ? loadedFile.getSamples().clone() : null,
                capturePreset()));
        restoreEditState(prev, "Undo.");
        updateUndoRedoButtons();
    }

    @FXML
    private void onRedo()
    {
        if (exporting) return;
        if (loadedFile == null || redoStack.isEmpty()) return;
        EditState next = redoStack.pop();
        undoStack.push(new EditState(
                (next.samples != null) ? loadedFile.getSamples().clone() : null,
                capturePreset()));
        restoreEditState(next, "Redo.");
        updateUndoRedoButtons();
    }

    /** Restores one history entry: parameters always, samples when present. */
    private void restoreEditState(EditState state, String message)
    {
        if (state.params != null) applyPreset(state.params);
        if (state.samples != null)
        {
            loadedFile.setSamples(state.samples.clone());
            afterTrim(message);
        }
        else
        {
            scheduleDynamicsRefreshAfterPresetApply();
            setStatus(message);
        }
    }

    /* =========================================================
     *  Peak re-analysis and LUFS measurement (Tasks)
     * ========================================================= */

    /**
     * Recomputes the Peak Normalizer's gain so it reflects the current
     * upstream state. The upstream that affects the peak live is the Fade
     * (the EQ has no UI yet and is transparent); it is simulated inline on
     * a copy of the source <i>without clamping</i>, so the analysis sees
     * the true maximum the normalizer must bring to target. The new factor
     * lands in the {@link PeakNormalizer}'s volatile gain and is picked up
     * by the audio thread on its next buffer. (Offline export already
     * analyses the full post-upstream signal - the EQ included - through
     * the pipeline.)
     */
    private void runPeakAnalysis() { measureOutput(); }

    /**
     * Re-analyses the chain and updates the Peak Normalizer's peak (and the
     * meters) on demand. The normalizer reads its peak after all upstream
     * processing, so this reflects whatever the dynamics are doing.
     */
    @FXML
    private void onRecalculatePeak()
    {
        if (loadedFile == null) return;
        setStatus("Recalculating peak…");
        syncLiveAnalysis(() -> setStatus("Peak recalculated."));
    }

    /**
     * Runs an integrated LUFS measurement against the current
     * audio (post-trim) on a background task.
     */
    private void runLufsMeasurement()
    {
        measureOutput();
    }

    /**
     * Measures the processed OUTPUT (the real master). The measurement now
     * rides on the single cumulative analysis pass: {@link #syncLiveAnalysis}
     * renders the chain once, adopts the envelopes onto the live processors
     * and reports LUFS / LRA / true peak / stereo image from that same render.
     */
    private void measureOutput()
    {
        syncLiveAnalysis();
    }

    /** Updates the live meters from the drained playback buffers. */
    private void updateLiveMeters()
    {
        int ch = (loadedFile != null) ? loadedFile.getChannels() : 2;
        if (liveTp == null || liveTp.length != ch)
        {
            liveTp = new com.dspark.analysis.TruePeak[ch];
            for (int c = 0; c < ch; c++) liveTp[c] = new com.dspark.analysis.TruePeak();
        }
        float[] buf;
        float[] lastBuf = null;
        while ((buf = meterQueue.poll()) != null)
        {
            liveMeter.process(buf, ch);
            liveSpectrum.push(buf, ch);
            feedStereoImage(buf, ch);
            int frames = buf.length / ch;
            for (int f = 0; f < frames; f++)
            {
                for (int c = 0; c < ch; c++)
                {
                    double tp = liveTp[c].process(buf[f * ch + c]);
                    if (tp > livePeak) livePeak = tp;
                }
            }
            lastBuf = buf;
        }
        liveSpectrum.update();
        if (lastBuf != null) drawGoniometer(lastBuf, ch);

        meterLufs.setText(formatLufs(liveMeter.getIntegratedLufs()));
        meterShort.setText(formatLufs(liveMeter.getShortTermLufs()));
        meterMom.setText(formatLufs(liveMeter.getMomentaryLufs()));
        meterLra.setText(String.format(Locale.US, "%.1f LU", liveMeter.getLoudnessRange()));
        double pdb = (livePeak <= 0.0) ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(livePeak);
        meterPeak.setText(Double.isInfinite(pdb) ? "-∞ dBTP"
                : String.format(Locale.US, "%.1f dBTP", pdb));
        meterGr.setText(String.format(Locale.US, "%.1f dB", broadband.getGrAtPosition(player.getPositionSamples())));
        updateStereoImageLabels();
        livePeak *= 0.90;   // peak falls back
    }

    /* =========================================================
     *  Stereo image metering (correlation, M/S, goniometer)
     * ========================================================= */

    /** Accumulates the phase correlation and M/S power of one buffer (smoothed). */
    private void feedStereoImage(float[] buf, int ch)
    {
        if (ch < 2) { liveCorrSmooth = 1.0; return; }
        double sumLR = 0.0, sumL2 = 0.0, sumR2 = 0.0, midPow = 0.0, sidePow = 0.0;
        int frames = buf.length / ch;
        for (int f = 0; f < frames; f++)
        {
            double l = buf[f * ch], r = buf[f * ch + 1];
            sumLR += l * r;
            sumL2 += l * l;
            sumR2 += r * r;
            double m = (l + r) * 0.5, s = (l - r) * 0.5;
            midPow += m * m;
            sidePow += s * s;
        }
        double denom = Math.sqrt(sumL2 * sumR2);
        double corr = (denom > 1e-12) ? sumLR / denom : 1.0;
        liveCorrSmooth += (corr - liveCorrSmooth) * 0.25;
        liveMidPow  += (midPow / Math.max(1, frames) - liveMidPow) * 0.25;
        liveSidePow += (sidePow / Math.max(1, frames) - liveSidePow) * 0.25;
    }

    private void updateStereoImageLabels()
    {
        if (meterCorr != null)
            meterCorr.setText(String.format(Locale.US, "%+.2f", liveCorrSmooth));
        if (meterMid != null)
            meterMid.setText(formatPowerDb(liveMidPow));
        if (meterSide != null)
            meterSide.setText(formatPowerDb(liveSidePow));
    }

    private static String formatPowerDb(double meanPower)
    {
        if (meanPower <= 1e-10) return "-∞ dB";
        return String.format(Locale.US, "%.1f dB", 10.0 * Math.log10(meanPower));
    }

    /** Phase correlation of a whole buffer (for the offline render's readout). */
    private static double correlationOf(float[] buf, int ch)
    {
        if (ch < 2) return 1.0;
        double sumLR = 0.0, sumL2 = 0.0, sumR2 = 0.0;
        for (int i = 0; i + 1 < buf.length; i += ch)
        {
            double l = buf[i], r = buf[i + 1];
            sumLR += l * r;
            sumL2 += l * l;
            sumR2 += r * r;
        }
        double denom = Math.sqrt(sumL2 * sumR2);
        return (denom > 1e-12) ? sumLR / denom : 1.0;
    }

    /** Lissajous goniometer with persistence: M up, S sideways (45° rotation). */
    private void drawGoniometer(float[] buf, int ch)
    {
        if (gonioCanvas == null || ch < 2) return;
        GraphicsContext g = gonioCanvas.getGraphicsContext2D();
        double w = gonioCanvas.getWidth(), h = gonioCanvas.getHeight();
        g.setFill(Color.web("#14141a", 0.30));      // fade previous frame (persistence)
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.web("#2d2d35"));
        g.setLineWidth(1.0);
        g.strokeLine(0, h / 2.0, w, h / 2.0);
        g.strokeLine(w / 2.0, 0, w / 2.0, h);

        g.setFill(Color.web("#46d17a", 0.55));
        int frames = buf.length / ch;
        for (int f = 0; f < frames; f += 4)
        {
            double l = buf[f * ch], r = buf[f * ch + 1];
            double x = (l - r) * 0.7071;            // side axis
            double y = (l + r) * 0.7071;            // mid axis
            double px = w / 2.0 + x * (w / 2.0) * 0.92;
            double py = h / 2.0 - y * (h / 2.0) * 0.92;
            g.fillOval(px - 0.8, py - 0.8, 1.6, 1.6);
        }
    }

    /** Clears the goniometer display (when playback stops). */
    private void resetGoniometer()
    {
        if (gonioCanvas == null) return;
        GraphicsContext g = gonioCanvas.getGraphicsContext2D();
        g.setFill(Color.web("#14141a"));
        g.fillRect(0, 0, gonioCanvas.getWidth(), gonioCanvas.getHeight());
    }

    /** Formats an integrated / short-term / momentary loudness value. */
    private static String formatLufs(double lufs)
    {
        return (lufs <= LoudnessMeter.SILENCE_LUFS)
                ? "silence" : String.format(Locale.US, "%.1f LUFS", lufs);
    }

    /* =========================================================
     *  Equalizer UI (the unified first block)
     * ========================================================= */

    /** Half-range of the EQ curve display, in dB. */
    private static final double EQ_DB_RANGE = 24.0;
    private static final double EQ_MIN_HZ = 20.0;
    private static final double EQ_MAX_HZ = 20000.0;
    private static final double EQ_KNOB_SCALE = 0.8;   // compact knobs (band + Auto EQ in one row)
    /** Pivot frequency of a tilt band - the pink-noise rotation point. */
    private static final double TILT_PIVOT_HZ = 650.0;

    /**
     * Wires the equalizer panel: fills the type / route / phase choosers,
     * installs listeners that push editor changes into the engine and
     * redraw the response curve, and prepares the canvas (resize + click /
     * drag editing). The per-band editor starts disabled until a band is
     * added.
     */
    private void initEqUi()
    {
        eqType.getItems().setAll(MasterEqualizer.BandType.values());
        eqChannel.getItems().setAll(MasterEqualizer.Channel.values());
        eqPhase.getItems().setAll(MasterEqualizer.BandPhase.values());
        eqType.setConverter(prettyEnumConverter());
        eqChannel.setConverter(prettyEnumConverter());
        eqPhase.setConverter(prettyEnumConverter());
        eqSlope.getItems().setAll(6, 12, 24, 36, 48);
        eqSlope.setConverter(new StringConverter<Integer>()
        {
            @Override public String toString(Integer v) { return v == null ? "" : v + " dB/oct"; }
            @Override public Integer fromString(String s) { return null; }
        });
        eqSlope.valueProperty().addListener((o, ov, nv) -> onEqEditorChanged());

        eqEnabled.selectedProperty().addListener((o, ov, nv) ->
        {
            eq.setEnabled(nv);
            drawEqCurve();
            scheduleDynamicsRefresh();
        });

        eqType.valueProperty().addListener((o, ov, nv) ->
        {
            // A fresh notch defaults to a clear, narrow cut so it's visible + adjustable.
            if (nv == MasterEqualizer.BandType.NOTCH && !updatingEqEditor
                    && Math.abs(kGain.getValue()) < 1.0)
            {
                kGain.setValue(-18.0);
                if (kQ.getValue() < 4.0) kQ.setValue(6.0);
            }
            updateFreqLockForType();
            onEqEditorChanged();
        });
        eqChannel.valueProperty().addListener((o, ov, nv) -> onEqEditorChanged());
        eqPhase.valueProperty().addListener((o, ov, nv)   -> onEqEditorChanged());
        eqDynamic.selectedProperty().addListener((o, ov, nv) ->
        {
            setEqDynControlsDisabled(!nv);
            updatePhaseForDynamic(nv);
            onEqEditorChanged();
        });
        // Knob editors (drag / wheel / double-click to type). Tooltips carry
        // the explanations so the UI itself stays uncluttered. Dynamic ranges
        // are SIGNED: + boosts/expands, - cuts/compresses.
        kFreq = new Knob("Freq", EQ_MIN_HZ, EQ_MAX_HZ, 1000.0).logarithmic()
                .formatter(MainController::formatHz)
                .tooltip("Band frequency. Drag / wheel, or double-click to type (1k = 1000 Hz).");
        kGain = new Knob("Gain", -EQ_DB_RANGE, EQ_DB_RANGE, 0.0)
                .formatter(MainController::formatSignedDb).tooltip("Band gain (dB).");
        kQ = new Knob("Q", 0.1, 10.0, 0.707).formatter(MainController::formatQ)
                .tooltip("Q: bandwidth / resonance (or cut slope shape).");
        kThreshold = new Knob("Thresh", -60.0, 0.0, -20.0)
                .formatter(v -> String.format(Locale.US, "%.0f dB", v))
                .tooltip("Dynamics threshold (dBFS): ABOVE acts when louder, BELOW when quieter.");
        // ABOVE-threshold section (amber).
        kAboveRange = new Knob("Range", -EQ_DB_RANGE, EQ_DB_RANGE, -6.0)
                .formatter(MainController::formatSignedDb).accent("#f0b14a")
                .tooltip("ABOVE threshold:range (dB): + boosts/expands, - cuts/compresses.");
        kAboveRatio = new Knob("Ratio", 1.0, 20.0, 4.0).formatter(MainController::formatRatio).accent("#f0b14a")
                .tooltip("ABOVE threshold:ratio (1:1 = off).");
        kAboveAtk = new Knob("Attack", 0.1, 200.0, 5.0).formatter(MainController::formatMs).accent("#f0b14a")
                .tooltip("ABOVE threshold:attack (ms).");
        kAboveRel = new Knob("Release", 1.0, 1000.0, 80.0).formatter(MainController::formatMs).accent("#f0b14a")
                .tooltip("ABOVE threshold:release (ms).");
        // BELOW-threshold section (green).
        kBelowRange = new Knob("Range", -EQ_DB_RANGE, EQ_DB_RANGE, 0.0)
                .formatter(MainController::formatSignedDb).accent("#46d17a")
                .tooltip("BELOW threshold:range (dB): + boosts/expands, - cuts/compresses.");
        kBelowRatio = new Knob("Ratio", 1.0, 20.0, 4.0).formatter(MainController::formatRatio).accent("#46d17a")
                .tooltip("BELOW threshold:ratio (1:1 = off).");
        kBelowAtk = new Knob("Attack", 0.1, 200.0, 10.0).formatter(MainController::formatMs).accent("#46d17a")
                .tooltip("BELOW threshold:attack (ms).");
        kBelowRel = new Knob("Release", 1.0, 1000.0, 120.0).formatter(MainController::formatMs).accent("#46d17a")
                .tooltip("BELOW threshold:release (ms).");

        for (Knob k : new Knob[] { kFreq, kGain, kQ, kThreshold,
                kAboveRange, kAboveRatio, kAboveAtk, kAboveRel,
                kBelowRange, kBelowRatio, kBelowAtk, kBelowRel })
        {
            k.scale(EQ_KNOB_SCALE);                  // compact: fit band + Auto EQ on one row
            k.valueProperty().addListener((o, ov, nv) -> onEqEditorChanged());
        }
        // Live level meter beside the threshold (detector level + threshold marker).
        levelBar = new LevelBar().range(-60.0, 0.0);
        kThreshold.valueProperty().addListener((o, ov, nv) -> levelBar.setThreshold(nv.doubleValue()));
        levelBar.setThreshold(kThreshold.getValue());

        // All band + dynamic knobs in ONE row, the sections spread evenly across
        // the width by expanding dividers, each section labelled underneath.
        eqKnobs.getChildren().setAll(
                labeledGroup("BAND", "#4a9eff", kFreq, kGain, kQ), growingDivider(),
                labeledGroup("THRESH", "#8b9bb5", kThreshold, levelBar), growingDivider(),
                labeledGroup("ABOVE", "#f0b14a", kAboveRange, kAboveRatio, kAboveAtk, kAboveRel), growingDivider(),
                labeledGroup("BELOW", "#46d17a", kBelowRange, kBelowRatio, kBelowAtk, kBelowRel));

        // Peak Normalizer target slider (right panel). A horizontal slider reads
        // more naturally here than a knob: it maps directly to "scale the peak up
        // to this dBFS ceiling", and carries the same audio-style shortcuts as the
        // limiter sliders (wheel / arrows / Shift-Ctrl steps / double-click reset).
        peakTarget.valueProperty().addListener((o, ov, nv) ->
        {
            normalizer.setTargetDbfs(nv.doubleValue());
            peakTargetLabel.setText(String.format(Locale.US, "%.1f dBTP", nv.doubleValue()));
            if (peakEnabled.isSelected())
                peakAppliedLabel.setText(formatSignedDb(normalizer.getGainDb()));
        });
        normalizer.setTargetDbfs(peakTarget.getValue());
        peakTargetLabel.setText(String.format(Locale.US, "%.1f dBTP", peakTarget.getValue()));
        installAudioSliderShortcuts(peakTarget, 1.0, 0.5, 0.1, PeakNormalizer.DEFAULT_TARGET_DBFS);

        eqBandSelector.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) ->
        {
            if (updatingEqEditor) return;
            int i = nv.intValue();
            if (i >= 0 && i < eqBandCount)
            {
                eqSelectedBand = i;
                updatingEqEditor = true;
                loadBandToEditor(i);
                updatingEqEditor = false;
                drawEqCurve();
            }
        });

        eqCanvasWrapper.widthProperty().addListener((o, ov, nv) ->
        {
            eqCanvas.setWidth(nv.doubleValue());
            drawEqCurve();
        });
        eqCanvasWrapper.heightProperty().addListener((o, ov, nv) ->
        {
            eqCanvas.setHeight(nv.doubleValue());
            drawEqCurve();
        });
        eqCanvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onEqCanvasPressed);
        eqCanvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onEqCanvasDragged);
        eqCanvas.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onEqCanvasReleased);
        eqCanvas.addEventHandler(MouseEvent.MOUSE_MOVED, this::onEqCanvasMoved);
        eqCanvas.addEventHandler(ScrollEvent.SCROLL, this::onEqCanvasScroll);

        initBandMenu();
        setEqEditorDisabled(true);

        // Animate the curve while playing so dynamic-band handles move with
        // the gain, and keep the limiter GR meter live.
        eqAnimator = new AnimationTimer()
        {
            private long last = 0L;
            @Override
            public void handle(long now)
            {
                if (now - last < 33_000_000L) return;   // ~30 fps
                last = now;
                // Redraw the EQ while its panel is showing so the live spectrum,
                // the Auto EQ correction and any dynamic-band handles all animate.
                if (selectedModule != null && selectedModule.processors.contains(eq))
                {
                    drawEqCurve();
                }
                updateLiveMeters();
                updateDynamicsMeters();
                updateClipMeters();
                updateLimiterMeters();
                // Feed the live detector level into the threshold knob (reference).
                if (eqSelectedBand >= 0)
                {
                    MasterEqualizer.Band b = eq.getBand(eqSelectedBand);
                    levelBar.setLevel((b != null && b.dynamic)
                            ? eq.getBandDetectorDb(eqSelectedBand) : Double.NaN);
                }
            }
        };
        player.playingProperty().addListener((o, ov, nv) ->
        {
            if (nv)
            {
                liveMeter.prepare(loadedFile != null ? loadedFile.getSampleRate() : 48000);
                liveSpectrum.setSampleRate(loadedFile != null ? loadedFile.getSampleRate() : 48000);
                liveSpectrum.reset();
                livePeak = 0.0;
                meterQueue.clear();
                eqAnimator.start();
            }
            else
            {
                eqAnimator.stop();
                levelBar.setLevel(Double.NaN);     // clear the threshold reference
                resetDynamicsMeters();             // clear the per-card -GR meters
                resetClipMeters();
                drawEqCurve();                     // clear the (now-stale) live dynamic curve
            }
        });

        Platform.runLater(this::drawEqCurve);
    }

    /** Pushes the editor's values into the selected band and redraws. */
    private void onEqEditorChanged()
    {
        if (updatingEqEditor || eqSelectedBand < 0) return;
        applyEditorToBand();
        drawEqCurve();
        // EQ edits change everything downstream (normalizer gain, comp
        // envelopes), so they re-analyse like every other parameter.
        scheduleDynamicsRefresh();
    }

    /**
     * Adds a new bell band (transparent until adjusted) and selects it.
     * FXML handler for the "+ Band" button.
     */
    @FXML
    private void onAddBand()
    {
        if (eqBandCount >= eq.getMaxBands()) return;
        int i = eqBandCount++;
        eq.setBand(i, new MasterEqualizer.Band());   // Bell, Stereo, Linear, 1 kHz, 0 dB

        updatingEqEditor = true;
        refreshBandSelector();
        eqBandSelector.getSelectionModel().select(i);
        eqSelectedBand = i;
        loadBandToEditor(i);
        updatingEqEditor = false;

        setEqEditorDisabled(false);
        drawEqCurve();
        scheduleDynamicsRefresh();
        setStatus("EQ band " + (i + 1) + " added.");
    }

    /**
     * Removes the selected band, compacting the higher bands down over it. The
     * previous band (if any) becomes selected and keeps the floating menu open.
     */
    @FXML
    private void onRemoveBand()
    {
        if (eqSelectedBand < 0 || eqBandCount == 0) return;
        for (int j = eqSelectedBand; j < eqBandCount - 1; j++)
        {
            eq.setBand(j, eq.getBand(j + 1));
        }
        eqBandCount--;
        eq.setNumBands(eqBandCount);

        updatingEqEditor = true;
        refreshBandSelector();
        if (eqBandCount == 0)
        {
            eqSelectedBand = -1;
            showBandMenu = false;
            setEqEditorDisabled(true);
        }
        else
        {
            eqSelectedBand = Math.max(0, eqSelectedBand - 1);   // select the previous band
            eqBandSelector.getSelectionModel().select(eqSelectedBand);
            loadBandToEditor(eqSelectedBand);
            showBandMenu = true;                                // keep the floating menu on it
        }
        updatingEqEditor = false;
        drawEqCurve();
        scheduleDynamicsRefresh();
        setStatus("EQ band removed.");
    }

    /** Reads the editor controls into the selected band. */
    private void applyEditorToBand()
    {
        if (eqSelectedBand < 0) return;
        MasterEqualizer.Band b = eq.getBand(eqSelectedBand);
        if (b == null) return;
        b.type    = eqType.getValue();
        b.channel = eqChannel.getValue();
        b.phase   = eqPhase.getValue();
        // A tilt rotates the spectrum around a fixed pink-noise pivot.
        b.frequency = (b.type == MasterEqualizer.BandType.TILT)
                ? TILT_PIVOT_HZ : kFreq.getValue();
        b.gainDb  = kGain.getValue();
        b.q       = kQ.getValue();
        b.slope   = (eqSlope.getValue() != null) ? eqSlope.getValue() : 12;
        // b.enabled (mute) is controlled by the floating band menu, not this editor.
        b.dynamic = eqDynamic.isSelected();
        b.threshold = kThreshold.getValue();
        double ar = kAboveRange.getValue();         // signed: + boost/expand, - cut/compress
        b.aboveRangeDb   = Math.abs(ar);
        b.aboveBoost     = ar >= 0.0;
        b.aboveRatio     = kAboveRatio.getValue();
        b.aboveAttackMs  = kAboveAtk.getValue();
        b.aboveReleaseMs = kAboveRel.getValue();
        double br = kBelowRange.getValue();
        b.belowRangeDb   = Math.abs(br);
        b.belowBoost     = br >= 0.0;
        b.belowRatio     = kBelowRatio.getValue();
        b.belowAttackMs  = kBelowAtk.getValue();
        b.belowReleaseMs = kBelowRel.getValue();
        eq.setBand(eqSelectedBand, b);
    }

    /** Loads a band's parameters into the editor. Caller must hold {@code updatingEqEditor}. */
    private void loadBandToEditor(int i)
    {
        MasterEqualizer.Band b = eq.getBand(i);
        if (b == null) return;
        eqType.setValue(b.type);
        eqChannel.setValue(b.channel);
        eqPhase.setValue(b.phase);
        kFreq.setValue(b.frequency);
        kGain.setValue(b.gainDb);
        kQ.setValue(b.q);
        eqSlope.setValue(b.slope);
        eqDynamic.setSelected(b.dynamic);
        kThreshold.setValue(b.threshold);
        kAboveRange.setValue(b.aboveBoost ? b.aboveRangeDb : -b.aboveRangeDb);
        kAboveRatio.setValue(b.aboveRatio);
        kAboveAtk.setValue(b.aboveAttackMs);
        kAboveRel.setValue(b.aboveReleaseMs);
        kBelowRange.setValue(b.belowBoost ? b.belowRangeDb : -b.belowRangeDb);
        kBelowRatio.setValue(b.belowRatio);
        kBelowAtk.setValue(b.belowAttackMs);
        kBelowRel.setValue(b.belowReleaseMs);
        setEqDynControlsDisabled(!b.dynamic);
        updatePhaseForDynamic(b.dynamic);
        updateFreqLockForType();
    }

    /** Dynamic bands are intrinsically minimum-phase, so force + lock the phase. */
    private void updatePhaseForDynamic(boolean dynamic)
    {
        if (dynamic)
        {
            boolean wasUpdating = updatingEqEditor;
            updatingEqEditor = true;
            eqPhase.setValue(MasterEqualizer.BandPhase.MINIMUM);
            updatingEqEditor = wasUpdating;
        }
        eqPhase.setDisable(dynamic);
    }

    /** Greys out the dynamic-EQ knobs unless the band is dynamic. */
    private void setEqDynControlsDisabled(boolean disabled)
    {
        kThreshold.setDisable(disabled);
        kAboveRange.setDisable(disabled);
        kAboveRatio.setDisable(disabled);
        kAboveAtk.setDisable(disabled);
        kAboveRel.setDisable(disabled);
        kBelowRange.setDisable(disabled);
        kBelowRatio.setDisable(disabled);
        kBelowAtk.setDisable(disabled);
        kBelowRel.setDisable(disabled);
    }

    /** Locks the frequency control for a tilt band (fixed pink-noise pivot). */
    private void updateFreqLockForType()
    {
        boolean tilt = eqType.getValue() == MasterEqualizer.BandType.TILT;
        kFreq.setDisable(tilt);
        if (tilt)
        {
            boolean wasUpdating = updatingEqEditor;
            updatingEqEditor = true;
            kFreq.setValue(TILT_PIVOT_HZ);
            updatingEqEditor = wasUpdating;
        }
        // Slope only applies to cut bands.
        MasterEqualizer.BandType type = eqType.getValue();
        eqSlope.setDisable(type != MasterEqualizer.BandType.LOW_CUT
                && type != MasterEqualizer.BandType.HIGH_CUT);
    }

    /** Friendly combo display: {@code LOW_SHELF} → "Low Shelf". */
    private static <T> StringConverter<T> prettyEnumConverter()
    {
        return new StringConverter<T>()
        {
            @Override public String toString(T v) { return v == null ? "" : prettify(v.toString()); }
            @Override public T fromString(String s) { return null; }
        };
    }

    private static String prettify(String enumName)
    {
        String[] parts = enumName.toLowerCase(Locale.US).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts)
        {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /** Rebuilds the band chooser's item list. Caller must hold {@code updatingEqEditor}. */
    private void refreshBandSelector()
    {
        eqBandSelector.getItems().clear();
        for (int i = 0; i < eqBandCount; i++)
        {
            eqBandSelector.getItems().add("Band " + (i + 1));
        }
    }

    /** Enables/disables the per-band editor controls together. */
    private void setEqEditorDisabled(boolean disabled)
    {
        eqType.setDisable(disabled);
        eqChannel.setDisable(disabled);
        eqPhase.setDisable(disabled);
        kFreq.setDisable(disabled);
        kGain.setDisable(disabled);
        kQ.setDisable(disabled);
        eqSlope.setDisable(disabled);
        eqDynamic.setDisable(disabled);
        if (disabled) setEqDynControlsDisabled(true);
    }

    /**
     * Redraws the EQ response: a dB / log-frequency grid, the combined
     * magnitude of all bands (across every routing domain, for display),
     * and a handle per band (the selected one highlighted).
     */
    private static final double[] EQ_FREQ_TICKS =
            { 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000 };
    private static final String[] EQ_FREQ_LABELS =
            { "20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k" };

    /**
     * Draws the EQ response: dB / log-frequency grid with axis labels, the
     * filled <b>static</b> magnitude curve (blue), a live <b>dynamic</b> curve
     * over it (amber) when any band is dynamic, and one draggable handle per
     * band at its static gain (dynamic bands ringed).
     */
    private void drawEqCurve()
    {
        if (eqCanvas == null) return;
        GraphicsContext gc = eqCanvas.getGraphicsContext2D();
        double w = eqCanvas.getWidth();
        double h = eqCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        gc.setFill(Color.web("#1a1a1f"));   // same as the base background (no surface)
        gc.fillRect(0, 0, w, h);
        gc.setFont(Font.font(10.5));

        // dB grid + labels.
        for (int db = -24; db <= 24; db += 6)
        {
            double y = eqDbToY(db, h);
            gc.setStroke(db == 0 ? Color.web("#4a4a58") : Color.web("#2a2a34"));
            gc.setLineWidth(db == 0 ? 1.3 : 1.0);
            gc.strokeLine(0, y, w, y);
            if (db != 0)
            {
                gc.setFill(Color.web("#9aa0ad"));
                gc.fillText((db > 0 ? "+" : "") + db, 3, y - 2);
            }
        }
        // Frequency grid + labels.
        gc.setStroke(Color.web("#2a2a34"));
        gc.setLineWidth(1.0);
        for (int i = 0; i < EQ_FREQ_TICKS.length; i++)
        {
            double x = eqFreqToX(EQ_FREQ_TICKS[i], w);
            gc.strokeLine(x, 0, x, h);
            gc.setFill(Color.web("#6a6a74"));
            gc.fillText(EQ_FREQ_LABELS[i], x + 2, h - 3);
        }

        int n = Math.max(2, (int) w);
        double[] freqs = new double[n];
        double[] tmp   = new double[n];
        double logSpan = Math.log10(EQ_MAX_HZ / EQ_MIN_HZ);
        for (int k = 0; k < n; k++)
            freqs[k] = EQ_MIN_HZ * Math.pow(10.0, logSpan * k / (n - 1));

        drawSpectrum(gc, freqs, n, h);
        drawAutoEqCorrection(gc, freqs, n, h);

        boolean on = eqEnabled.isSelected();

        // Static combined magnitude (across all routing domains).
        double[] staticMag = new double[n];
        java.util.Arrays.fill(staticMag, 1.0);
        if (on)
        {
            for (MasterEqualizer.Channel dom : MasterEqualizer.Channel.values())
            {
                eq.getMagnitudeResponse(dom, freqs, tmp);
                for (int k = 0; k < n; k++) staticMag[k] *= tmp[k];
            }
        }
        drawEqMagnitude(gc, staticMag, n, h, Color.web("#4a9eff"), true);

        // Muted bands keep their curve, drawn dimmed (so you still see what they do).
        if (on)
        {
            for (int i = 0; i < eqBandCount; i++)
            {
                MasterEqualizer.Band b = eq.getBand(i);
                if (b == null || b.enabled) continue;
                eq.getBandMagnitudeResponse(i, freqs, tmp);
                drawEqMagnitude(gc, tmp, n, h, Color.web("#6f7787", 0.75), false);
            }
        }

        // Live dynamic magnitude over the static one (only while playing).
        boolean dyn = on && player.isPlaying() && eq.hasActiveDynamicBand();
        if (dyn)
        {
            double[] dynMag = new double[n];
            java.util.Arrays.fill(dynMag, 1.0);
            for (MasterEqualizer.Channel dom : MasterEqualizer.Channel.values())
            {
                eq.getMagnitudeResponse(dom, freqs, tmp, true);
                for (int k = 0; k < n; k++) dynMag[k] *= tmp[k];
            }
            drawEqMagnitude(gc, dynMag, n, h, Color.web("#f0b14a"), false);
        }

        // Band handles (draggable; hit-test matches). Muted bands stay visible, dimmed.
        for (int i = 0; i < eqBandCount; i++)
        {
            MasterEqualizer.Band b = eq.getBand(i);
            if (b == null) continue;
            double bx = eqFreqToX(b.frequency, w);
            // Handle sits ON the actual response curve (same point the hit-test uses),
            // so shelves/tilt/notch are grabbable where the curve is.
            double by = eqBandHandleY(b, h);
            boolean sel = (i == eqSelectedBand);
            if (!b.enabled)                                  // muted: dim, hollow marker
            {
                gc.setStroke(Color.web("#6f7787"));
                gc.setLineWidth(1.5);
                gc.strokeOval(bx - 5, by - 5, 10, 10);
                continue;
            }
            if (b.dynamic)
            {
                gc.setStroke(Color.web("#f0b14a"));
                gc.setLineWidth(1.5);
                gc.strokeOval(bx - 8.5, by - 8.5, 17, 17);
            }
            gc.setFill(sel ? Color.web("#f5c46a") : Color.web("#4a9eff"));
            double r = sel ? 6 : 5;
            gc.fillOval(bx - r, by - r, 2 * r, 2 * r);
        }

        positionBandMenu();
    }

    /**
     * Draws the spectrum analyser behind the EQ: a live, moving curve during
     * playback, or the static long-term average when stopped.
     */
    private void drawSpectrum(GraphicsContext gc, double[] freqs, int n, double h)
    {
        boolean live = player.isPlaying() && liveSpectrum.isReady();
        if (!live && !spectrumAnalysis.isReady()) return;
        double top = (live ? liveSpectrum.getMaxDb() : spectrumAnalysis.getMaxDb()) + 6.0;
        double range = 80.0;

        float[] ys = new float[n];
        for (int k = 0; k < n; k++)
        {
            double db = live ? liveSpectrum.levelDbAt(freqs[k]) : spectrumAnalysis.levelDbAt(freqs[k]);
            double y = h * (top - db) / range;
            ys[k] = (float) (y < 0 ? 0 : (y > h ? h : y));
        }

        gc.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true,
                javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0.0, Color.web("#7da6d8", 0.34)),
                new javafx.scene.paint.Stop(1.0, Color.web("#7da6d8", 0.04))));
        gc.beginPath();
        gc.moveTo(0, h);
        for (int k = 0; k < n; k++) gc.lineTo(k, ys[k]);
        gc.lineTo(n - 1, h);
        gc.closePath();
        gc.fill();

        gc.setStroke(Color.web("#9fc0e8", 0.6));
        gc.setLineWidth(1.2);
        gc.beginPath();
        for (int k = 0; k < n; k++) { if (k == 0) gc.moveTo(k, ys[k]); else gc.lineTo(k, ys[k]); }
        gc.stroke();
    }

    /** Draws one magnitude curve (optionally filled to the 0-dB line). */
    private void drawEqMagnitude(GraphicsContext gc, double[] mag, int n, double h,
                                 Color color, boolean fill)
    {
        if (fill)
        {
            gc.setFill(Color.web("#4a9eff", 0.13));
            gc.beginPath();
            gc.moveTo(0, h / 2.0);
            for (int k = 0; k < n; k++)
                gc.lineTo(k, eqDbToY(20.0 * Math.log10(Math.max(mag[k], 1e-6)), h));
            gc.lineTo(n - 1, h / 2.0);
            gc.closePath();
            gc.fill();
        }
        gc.setStroke(color);
        gc.setLineWidth(2.0);
        gc.beginPath();
        for (int k = 0; k < n; k++)
        {
            double y = eqDbToY(20.0 * Math.log10(Math.max(mag[k], 1e-6)), h);
            if (k == 0) gc.moveTo(k, y); else gc.lineTo(k, y);
        }
        gc.stroke();
    }

    /* =========================================================
     *  Auto EQ row
     * ========================================================= */

    private void initAutoEqUi()
    {
        if (eqKnobs == null || autoEqOn == null) return;
        autoEqOn.setSelected(autoEq.isEnabled());
        autoEqOn.selectedProperty().addListener((o, ov, nv) ->
        {
            autoEq.setEnabled(nv);
            setAutoEqControlsDisabled(!nv);
            drawEqCurve();
            scheduleDynamicsRefresh();
        });

        autoEqTarget = new ComboBox<>();
        autoEqTarget.getItems().setAll(AutoEqProcessor.Target.values());
        autoEqTarget.getSelectionModel().select(autoEq.getTarget());
        autoEqTarget.setPrefWidth(86);
        autoEqTarget.valueProperty().addListener((o, ov, nv) ->
        {
            if (nv != null) { autoEq.setTarget(nv); drawEqCurve(); scheduleDynamicsRefresh(); }
        });

        Knob amount = autoEqKnob("Amount", AutoEqProcessor.MIN_AMOUNT, AutoEqProcessor.MAX_AMOUNT,
                autoEq.getAmount(), false, v -> String.format(Locale.US, "%.0f %%", v * 100),
                "How strongly to pull the spectrum toward the target curve.", autoEq::setAmount);
        Knob attack = autoEqKnob("Attack", AutoEqProcessor.MIN_ATTACK_SEC, AutoEqProcessor.MAX_ATTACK_SEC,
                autoEq.getAttackSec(), true, this::fmtEqTime,
                "How fast a band engages when the spectrum departs from the target.", autoEq::setAttackSec);
        Knob release = autoEqKnob("Release", AutoEqProcessor.MIN_RELEASE_SEC, AutoEqProcessor.MAX_RELEASE_SEC,
                autoEq.getReleaseSec(), true, this::fmtEqTime,
                "How slowly a band relaxes back.", autoEq::setReleaseSec);
        autoEqAmountKnob = amount;
        autoEqAttackKnob = attack;
        autoEqReleaseKnob = release;

        // Auto EQ section: label on top, the knobs, then the target selector below.
        Label title = new Label("AUTO EQ");
        title.setStyle("-fx-text-fill: #46c1a6; -fx-font-size: 9.5px; -fx-font-weight: bold;");
        HBox knobs = new HBox(4, amount, attack, release);
        knobs.setAlignment(Pos.CENTER);
        HBox comboRow = new HBox(autoEqTarget);
        comboRow.setAlignment(Pos.CENTER);
        VBox group = new VBox(6, title, knobs, comboRow);
        group.setAlignment(Pos.TOP_CENTER);
        group.setPadding(new javafx.geometry.Insets(14, 14, 14, 14));   // symmetric → group centred in its box
        eqKnobs.getChildren().addAll(growingDivider(), group);

        autoEqControls = new Node[] { amount, attack, release, autoEqTarget };
        setAutoEqControlsDisabled(!autoEq.isEnabled());     // dim when off (like the dynamic section)
    }

    /** Greys out the Auto EQ controls unless Auto EQ is on. */
    private void setAutoEqControlsDisabled(boolean disabled)
    {
        if (autoEqControls == null) return;
        for (Node c : autoEqControls) c.setDisable(disabled);
    }

    private Knob autoEqKnob(String label, double min, double max, double init, boolean log,
                            Knob.Formatter fmt, String tip, java.util.function.DoubleConsumer setter)
    {
        Knob k = new Knob(label, min, max, init);
        k.scale(EQ_KNOB_SCALE).accent("#46c1a6").formatter(fmt).tooltip(tip);
        if (log) k.logarithmic();
        k.valueProperty().addListener((o, ov, nv) -> { setter.accept(nv.doubleValue()); scheduleDynamicsRefresh(); });
        return k;
    }

    private String fmtEqTime(double sec)
    {
        return (sec < 1.0) ? String.format(Locale.US, "%.0f ms", sec * 1000.0)
                           : String.format(Locale.US, "%.2f s", sec);
    }

    /** Draws the Auto EQ correction actually applied at the playhead, on the EQ's dB axis. */
    private void drawAutoEqCorrection(GraphicsContext gc, double[] freqs, int n, double h)
    {
        if (autoEqOn == null || !autoEqOn.isSelected()) return;
        double[] corr = new double[n];
        autoEq.fillCorrection(freqs, player.getPositionSamples(), corr);

        double zeroY = eqDbToY(0.0, h);
        gc.setFill(Color.web("#46c1a6", 0.16));
        gc.beginPath();
        gc.moveTo(0, zeroY);
        for (int k = 0; k < n; k++) gc.lineTo(k, eqDbToY(corr[k], h));
        gc.lineTo(n - 1, zeroY);
        gc.closePath();
        gc.fill();

        gc.setStroke(Color.web("#46c1a6", 0.9));
        gc.setLineWidth(2.0);
        gc.beginPath();
        for (int k = 0; k < n; k++)
        {
            double y = eqDbToY(corr[k], h);
            if (k == 0) gc.moveTo(k, y); else gc.lineTo(k, y);
        }
        gc.stroke();
    }

    /** Shows a hand cursor while the pointer is over a grabbable band. */
    private void onEqCanvasMoved(MouseEvent e)
    {
        double w = eqCanvas.getWidth(), h = eqCanvas.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean overBand = eqBandUnderCursor(e.getX(), e.getY(), w, h) >= 0;
        eqCanvas.setCursor(overBand ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
    }

    /**
     * Mouse press on the EQ curve. Single-click selects the nearest band and
     * begins dragging it; double-click on a band deletes it; double-click on
     * empty space creates a band there.
     */
    private void onEqCanvasPressed(MouseEvent e)
    {
        double w = eqCanvas.getWidth(), h = eqCanvas.getHeight();
        if (w <= 0 || h <= 0) return;
        double x = e.getX(), y = e.getY();
        int hit = eqBandUnderCursor(x, y, w, h);

        if (e.getClickCount() == 2)
        {
            if (hit >= 0)
            {
                eqSelectedBand = hit;
                onRemoveBand();                 // double-click a band → delete
            }
            else if (eqBandCount < eq.getMaxBands())
            {
                int i = eqBandCount++;          // double-click empty → create here
                MasterEqualizer.Band b = new MasterEqualizer.Band();
                b.frequency = eqXToFreq(x, w);
                b.gainDb = clampEqGain(eqYToDb(y, h));
                eq.setBand(i, b);
                updatingEqEditor = true;
                refreshBandSelector();
                eqBandSelector.getSelectionModel().select(i);
                eqSelectedBand = i;
                loadBandToEditor(i);
                updatingEqEditor = false;
                setEqEditorDisabled(false);
                showBandMenu = true;            // show the menu on the new band
                drawEqCurve();
            }
            eqDragging = false;
            return;
        }

        if (hit >= 0)
        {
            showBandMenu = true;                // clicking a band shows its menu
            selectBand(hit);                    // redraws → places the floating menu
            eqDragging = true;
            eqDragAxis = 0;
            MasterEqualizer.Band b = eq.getBand(hit);
            eqDragVirtX = eqFreqToX(b.frequency, w);
            eqDragVirtY = eqDbToY(b.gainDb, h);
            eqDragLastX = x;
            eqDragLastY = y;
        }
        else
        {
            eqDragging = false;                 // click off a band: hide the menu
            showBandMenu = false;
            drawEqCurve();
        }
    }

    /**
     * Mouse drag on the EQ curve: moves the band in frequency (X) and gain (Y).
     * Hold <b>Shift</b> for a fine drag, <b>Alt</b> to constrain to one axis.
     */
    private void onEqCanvasDragged(MouseEvent e)
    {
        if (!eqDragging || eqSelectedBand < 0) return;
        double w = eqCanvas.getWidth(), h = eqCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        double x = e.getX(), y = e.getY();
        double dx = x - eqDragLastX, dy = y - eqDragLastY;
        eqDragLastX = x;
        eqDragLastY = y;

        if (e.isShiftDown()) { dx *= 0.25; dy *= 0.25; }    // fine
        if (e.isAltDown())
        {
            if (eqDragAxis == 0) eqDragAxis = (Math.abs(dx) >= Math.abs(dy)) ? 1 : 2;
        }
        else
        {
            eqDragAxis = 0;
        }
        if (eqDragAxis == 1) dy = 0;    // frequency only
        if (eqDragAxis == 2) dx = 0;    // gain only

        eqDragVirtX = Math.max(0.0, Math.min(w, eqDragVirtX + dx));
        eqDragVirtY = Math.max(0.0, Math.min(h, eqDragVirtY + dy));
        double freq = eqXToFreq(eqDragVirtX, w);
        double gain = clampEqGain(eqYToDb(eqDragVirtY, h));

        updatingEqEditor = true;
        kFreq.setValue(freq);
        kGain.setValue(gain);
        updatingEqEditor = false;
        applyEditorToBand();
        drawEqCurve();
    }

    /** Ends an EQ band drag. */
    private void onEqCanvasReleased(MouseEvent e)
    {
        if (eqDragging) scheduleDynamicsRefresh();   // the band drag is done: re-analyse
        eqDragging = false;
        eqDragAxis = 0;
    }

    /**
     * Mouse wheel over the EQ curve: adjusts the band under
     * the cursor - <b>Q</b> by default, <b>gain</b> with Ctrl/⌘, <b>dynamic
     * range</b> with Alt; hold <b>Shift</b> for a fine step.
     */
    private void onEqCanvasScroll(ScrollEvent e)
    {
        double w = eqCanvas.getWidth(), h = eqCanvas.getHeight();
        if (w <= 0 || h <= 0) return;
        int idx = eqBandUnderCursor(e.getX(), e.getY(), w, h);
        if (idx < 0) return;
        MasterEqualizer.Band b = eq.getBand(idx);
        if (b == null) return;

        double delta = e.getDeltaY();
        if (delta == 0.0) delta = e.getDeltaX();
        if (delta == 0.0) { e.consume(); return; }
        double dir = (delta > 0.0) ? 1.0 : -1.0;
        boolean fine = e.isShiftDown();

        if (e.isShortcutDown())          // Ctrl/Cmd → gain
        {
            b.gainDb = clampEqGain(b.gainDb + dir * (fine ? 0.25 : 1.0));
        }
        else if (e.isAltDown())          // Alt → dynamic range
        {
            double r = clampRange(b.aboveRangeDb + dir * (fine ? 0.25 : 1.0));
            b.aboveRangeDb = r;
            b.belowRangeDb = r;
        }
        else if (b.type == MasterEqualizer.BandType.LOW_CUT
                || b.type == MasterEqualizer.BandType.HIGH_CUT)   // cut → slope
        {
            int[] sl = { 6, 12, 24, 36, 48 };        // dB/oct
            int i = 0;
            for (int j = 0; j < sl.length; j++) if (sl[j] == b.slope) { i = j; break; }
            i += (int) dir;
            b.slope = sl[(i < 0) ? 0 : (i >= sl.length ? sl.length - 1 : i)];
        }
        else                             // default → Q
        {
            double factor = fine ? 1.03 : 1.12;
            b.q = clampQ(dir > 0.0 ? b.q * factor : b.q / factor);
        }

        eq.setBand(idx, b);
        selectBand(idx);
        scheduleDynamicsRefresh();
        e.consume();
    }

    /** Selects a band and loads it into the editor (redraws). */
    private void selectBand(int idx)
    {
        eqSelectedBand = idx;
        updatingEqEditor = true;
        eqBandSelector.getSelectionModel().select(idx);
        loadBandToEditor(idx);
        updatingEqEditor = false;
        drawEqCurve();
    }

    /** Index of the band handle nearest the cursor within a hit radius, or -1. */
    private int eqBandUnderCursor(double x, double y, double w, double h)
    {
        int hit = -1;
        double best = 50.0 * 50.0;                           // generous reach: grab the nearest band
        for (int i = 0; i < eqBandCount; i++)
        {
            MasterEqualizer.Band b = eq.getBand(i);
            if (b == null) continue;
            double dx = eqFreqToX(b.frequency, w) - x;
            double dy = (eqBandHandleY(b, h) - y) * 0.6;     // looser vertically (grab above/below too)
            double d2 = dx * dx + dy * dy;
            if (d2 < best) { best = d2; hit = i; }
        }
        return hit;
    }

    /** Combined static EQ response at one frequency, in dB. */
    private double eqCurveDb(double freq)
    {
        double[] f = { Math.max(EQ_MIN_HZ, Math.min(EQ_MAX_HZ, freq)) };
        double[] out = new double[1];
        double mag = 1.0;
        for (MasterEqualizer.Channel dom : MasterEqualizer.Channel.values())
        {
            eq.getMagnitudeResponse(dom, f, out);
            mag *= out[0];
        }
        return 20.0 * Math.log10(Math.max(mag, 1e-6));
    }

    /** Y of a band's handle: on the response curve when active, at its own gain when muted. */
    private double eqBandHandleY(MasterEqualizer.Band b, double h)
    {
        double db = b.enabled ? eqCurveDb(b.frequency) : clampEqGain(b.gainDb);
        double y = eqDbToY(db, h);
        return (y < 5) ? 5 : (y > h - 5 ? h - 5 : y);
    }

    // Inline styles (deterministic - avoids any CSS-class/border quirks on the overlay).
    private static final String BMENU_BTN =
            "-fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 2 8 2 8; -fx-cursor: hand; "
          + "-fx-background-radius: 4; -fx-background-color: #3a2327; -fx-text-fill: #ff6a6a;";
    private static final String BMENU_BTN_MUTED =
            "-fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 2 8 2 8; -fx-cursor: hand; "
          + "-fx-background-radius: 4; -fx-background-color: #e23b3b; -fx-text-fill: #ffffff;";

    /** Builds the floating menu (M = mute, X = erase) that appears over a clicked band. */
    private void initBandMenu()
    {
        if (eqCanvasWrapper == null) return;
        bandMuteBtn = new Button("M");
        bandMuteBtn.setStyle(BMENU_BTN);
        bandMuteBtn.setFocusTraversable(false);
        bandMuteBtn.setMinWidth(34); bandMuteBtn.setPrefWidth(34);   // equal width
        bandMuteBtn.setTooltip(new javafx.scene.control.Tooltip("Mute / un-mute this band"));
        bandMuteBtn.setOnAction(e -> toggleSelectedBandMute());
        Button erase = new Button("X");
        erase.setStyle(BMENU_BTN);
        erase.setFocusTraversable(false);
        erase.setMinWidth(34); erase.setPrefWidth(34);
        erase.setTooltip(new javafx.scene.control.Tooltip("Erase this band"));
        erase.setOnAction(e -> onRemoveBand());
        bandMenu = new HBox(4, bandMuteBtn, erase);
        bandMenu.getStyleClass().add("band-menu");
        // Managed: the Pane sizes it to its preferred size and honours layoutX/Y.
        bandMenu.setVisible(false);
        eqCanvasWrapper.getChildren().add(bandMenu);
    }

    /** Mutes / un-mutes the selected band (keeps it; doesn't delete it). */
    private void toggleSelectedBandMute()
    {
        if (eqSelectedBand < 0) return;
        MasterEqualizer.Band b = eq.getBand(eqSelectedBand);
        if (b == null) return;
        b.enabled = !b.enabled;
        eq.setBand(eqSelectedBand, b);
        drawEqCurve();
        scheduleDynamicsRefresh();
    }

    /** Floats the band menu above the clicked band's handle; hidden otherwise. */
    private void positionBandMenu()
    {
        if (bandMenu == null) return;
        MasterEqualizer.Band b = (showBandMenu && eqSelectedBand >= 0) ? eq.getBand(eqSelectedBand) : null;
        double w = eqCanvas.getWidth(), h = eqCanvas.getHeight();
        if (b == null || w <= 0 || h <= 0) { bandMenu.setVisible(false); return; }

        bandMuteBtn.setStyle(b.enabled ? BMENU_BTN : BMENU_BTN_MUTED);   // filled red when muted
        bandMenu.setVisible(true);
        bandMenu.applyCss();
        double mw = bandMenu.prefWidth(-1), mh = bandMenu.prefHeight(-1);
        double mx = eqFreqToX(b.frequency, w) - mw / 2.0;
        double my = eqBandHandleY(b, h) - mh - 12.0;        // above the handle
        if (my < 2.0) my = eqBandHandleY(b, h) + 14.0;      // flip below near the top
        if (mx < 2.0) mx = 2.0;
        if (mx + mw > w - 2.0) mx = w - mw - 2.0;
        bandMenu.setLayoutX(mx);
        bandMenu.setLayoutY(my);
    }

    private static double clampQ(double q)
    {
        if (q < 0.1) return 0.1;
        if (q > 10.0) return 10.0;
        return q;
    }

    private static double clampRange(double r)
    {
        if (r < 0.0) return 0.0;
        if (r > 30.0) return 30.0;
        return r;
    }

    private static double eqFreqToX(double freq, double w)
    {
        double t = Math.log10(freq / EQ_MIN_HZ) / Math.log10(EQ_MAX_HZ / EQ_MIN_HZ);
        return Math.max(0.0, Math.min(1.0, t)) * w;
    }

    private static double eqXToFreq(double x, double w)
    {
        double t = (w <= 0.0) ? 0.0 : Math.max(0.0, Math.min(1.0, x / w));
        return EQ_MIN_HZ * Math.pow(10.0, t * Math.log10(EQ_MAX_HZ / EQ_MIN_HZ));
    }

    private static double eqDbToY(double db, double h)
    {
        double mid = h / 2.0;
        return mid - (db / EQ_DB_RANGE) * mid * 0.9;
    }

    private static double eqYToDb(double y, double h)
    {
        double mid = h / 2.0;
        return ((mid - y) / (mid * 0.9)) * EQ_DB_RANGE;
    }

    private static double clampEqGain(double db)
    {
        if (db < -EQ_DB_RANGE) return -EQ_DB_RANGE;
        if (db >  EQ_DB_RANGE) return  EQ_DB_RANGE;
        return db;
    }

    /** Formats a frequency, switching to kHz above 1 kHz. */
    private static String formatHz(double hz)
    {
        return (hz >= 1000.0)
                ? String.format(Locale.US, "%.2f kHz", hz / 1000.0)
                : String.format(Locale.US, "%.0f Hz", hz);
    }

    /** Formats a Q value with two decimals. */
    private static String formatQ(double q)
    {
        return String.format(Locale.US, "%.2f", q);
    }

    /** Formats a signed dB value (e.g. "+3.0 dB", "-6.0 dB"). */
    private static String formatSignedDb(double db)
    {
        return String.format(Locale.US, "%+.1f dB", db);
    }

    /** Formats a time in milliseconds (e.g. "80 ms"). */
    private static String formatMs(double ms)
    {
        return String.format(Locale.US, "%.0f ms", ms);
    }

    /** Formats a dynamics ratio (e.g. "4.0:1"; 1.0 = off). */
    private static String formatRatio(double r)
    {
        return (r <= 1.001) ? "off" : String.format(Locale.US, "%.1f:1", r);
    }

    /** A thin vertical divider between knob groups in the single row. */
    private static Separator dynSep()
    {
        Separator s = new Separator(Orientation.VERTICAL);
        s.getStyleClass().add("knob-sep");
        return s;
    }

    /** An expanding divider: spreads the knob sections evenly, with a centred line. */
    private static HBox growingDivider()
    {
        HBox box = new HBox(dynSep());
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(Double.MAX_VALUE);     // grow to fill, so sections spread evenly
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** A group of knobs/controls with a coloured section label on top + side padding. */
    private static VBox labeledGroup(String label, String colorHex, Node... items)
    {
        HBox row = new HBox(4, items);
        row.setAlignment(Pos.CENTER);
        Label l = new Label(label);
        l.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 9.5px; -fx-font-weight: bold;");
        VBox v = new VBox(6, l, row);          // label on top
        v.setAlignment(Pos.TOP_CENTER);
        v.setPadding(new javafx.geometry.Insets(20, 14, 8, 14));  // space above the label + between sections
        return v;
    }

    /* =========================================================
     *  Processing-chain tab bar (the audio chain)
     * ========================================================= */

    /**
     * One module in the processing chain: its name, the {@link AudioProcessor}
     * it drives, its editor panel and its enable checkbox (the on/off source of
     * truth, mirrored by the chip in the chain bar).
     */
    private static final class ChainModule
    {
        final String name;
        final List<AudioProcessor> processors;   // the block's stages, in signal order
        final Region panel;
        final CheckBox enableBox;
        Node chip;

        ChainModule(String name, List<AudioProcessor> processors, Region panel, CheckBox enableBox)
        {
            this.name = name;
            this.processors = processors;
            this.panel = panel;
            this.enableBox = enableBox;
        }
    }

    /**
     * Builds the chain tab bar from the modules in signal order, keeps each
     * chip's lit/dimmed look in sync with its enable state, and selects the
     * equalizer.
     */
    private void initChainUi()
    {
        // Two movable chips: EQ (EQ + Fade) and Dynamics (the four compressors).
        // The user can drag Dynamics before EQ. The Peak Normalizer (right panel)
        // and the Limiter are pinned at the end of the chain (see rebuildPipeline).
        chainModules.clear();
        chainModules.add(new ChainModule("EQ", List.of(autoEq, eq, fade), eqPanel, eqEnabled));
        chainModules.add(new ChainModule("Dynamics", dynamicsOrder, dynamicsPanel, dynMasterEnabled));
        // Clip (Soft-Clip → Hard-Clip) then Limit, both movable. The Peak Normalizer
        // is pinned immediately before the Limiter wherever it ends up.
        chainModules.add(new ChainModule("Clip", List.of(softClip, hardClip), clipPanel, clipEnabled));
        chainModules.add(new ChainModule("Limit", List.of(multiband, broadband), limiterPanel, limEnabled));

        for (ChainModule m : chainModules)
        {
            m.enableBox.selectedProperty().addListener((o, ov, nv) ->
            {
                if (m.chip != null) styleChip(m);
            });
        }

        rebuildChainBar();
        selectModule(chainModules.get(0));
    }

    /** Rebuilds the chips in the chain bar to match {@link #chainModules}. */
    private void rebuildChainBar()
    {
        chainBar.getChildren().clear();
        for (int i = 0; i < chainModules.size(); i++)
        {
            ChainModule m = chainModules.get(i);
            if (i > 0)
            {
                Label arrow = new Label("›");
                arrow.getStyleClass().add("chain-arrow");
                chainBar.getChildren().add(arrow);
            }
            m.chip = buildChip(m);
            chainBar.getChildren().add(m.chip);
            styleChip(m);
        }
    }

    /** Creates one draggable chip (on/off LED + name) for a module. */
    /** Hover description for each chain chip. */
    private static String chainChipTooltipText(String name)
    {
        switch (name)
        {
            case "EQ":       return "Spectral processing: tone shaping and automatic EQ";
            case "Dynamics": return "Smart, automatic compression driven by your target";
            case "Clip":     return "Peak reduction through smart, automatic saturation and clipping";
            case "Limit":    return "Automatic two-stage true-peak limiter";
            default:         return name;
        }
    }

    private Node buildChip(ChainModule m)
    {
        Region led = new Region();
        led.getStyleClass().add("chip-led");
        led.setOnMouseClicked(ev ->
        {
            m.enableBox.setSelected(!m.enableBox.isSelected());
            ev.consume();
        });

        Label name = new Label(m.name);
        name.getStyleClass().add("chip-name");

        HBox chip = new HBox(6, led, name);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("chain-chip");
        chip.setOnMouseClicked(ev -> selectModule(m));

        Tooltip chipTip = new Tooltip(chainChipTooltipText(m.name));
        chipTip.setShowDelay(Duration.millis(250));
        chipTip.setShowDuration(Duration.INDEFINITE);
        chipTip.setHideDelay(Duration.millis(100));
        chipTip.setWrapText(true);
        chipTip.setMaxWidth(280);
        Tooltip.install(chip, chipTip);

        // Drag a chip to reorder the chain.
        chip.setOnDragDetected(ev ->
        {
            Dragboard db = chip.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(Integer.toString(chainModules.indexOf(m)));
            db.setContent(cc);
            ev.consume();
        });
        chip.setOnDragOver(ev ->
        {
            if (ev.getGestureSource() != chip && ev.getDragboard().hasString())
            {
                ev.acceptTransferModes(TransferMode.MOVE);
            }
            ev.consume();
        });
        chip.setOnDragDropped(ev ->
        {
            boolean ok = false;
            Dragboard db = ev.getDragboard();
            if (db.hasString())
            {
                reorderChain(Integer.parseInt(db.getString()), chainModules.indexOf(m));
                ok = true;
            }
            ev.setDropCompleted(ok);
            ev.consume();
        });
        return chip;
    }

    /** Applies the lit/dimmed + selected styling to a module's chip. */
    private void styleChip(ChainModule m)
    {
        if (m.chip == null) return;
        m.chip.getStyleClass().removeAll("chain-chip-on", "chain-chip-off", "chain-chip-selected");
        m.chip.getStyleClass().add(m.enableBox.isSelected() ? "chain-chip-on" : "chain-chip-off");
        if (m == selectedModule) m.chip.getStyleClass().add("chain-chip-selected");
    }

    /** Shows the selected module's panel and highlights its chip. */
    private void selectModule(ChainModule m)
    {
        selectedModule = m;
        for (ChainModule cm : chainModules)
        {
            cm.panel.setVisible(cm == m);
            cm.panel.setManaged(cm == m);
            styleChip(cm);
        }
        FadeTransition ft = new FadeTransition(Duration.millis(150), m.panel);
        ft.setFromValue(0.35);
        ft.setToValue(1.0);
        ft.play();
        if (m.processors.contains(eq)) drawEqCurve();
    }

    /** Moves a module within the chain and rebuilds the bar + the pipeline. */
    private void reorderChain(int from, int to)
    {
        if (from == to || from < 0 || to < 0
                || from >= chainModules.size() || to >= chainModules.size())
        {
            return;
        }
        ChainModule moved = chainModules.remove(from);
        chainModules.add(to, moved);
        rebuildChainBar();
        rebuildPipeline();
        selectModule(moved);     // open the moved module's panel so the change is obvious
        setStatus("Chain: " + chainOrderText());
    }

    /**
     * Rebuilds the pipeline from the current chain order and re-prepares,
     * resuming playback from where it was (a brief recalibration, not a reset
     * to the start).
     */
    private void rebuildPipeline()
    {
        boolean wasPlaying = player.isPlaying();
        int sr = (loadedFile != null) ? loadedFile.getSampleRate() : 0;
        double posSec = (sr > 0) ? player.getPositionSamples() / (double) sr : 0.0;

        player.stop();      // stop the audio thread before swapping the chain
        List<AudioProcessor> order = new ArrayList<>();
        for (ChainModule m : chainModules) order.addAll(m.processors);
        order.add(normalizer);   // Peak Normalizer pinned last (final true-peak output)
        pipeline.setProcessors(order);

        if (loadedFile != null)
        {
            player.prepare(loadedFile);
            runPeakAnalysis();
            if (posSec > 0.0) player.seekTo(posSec);
            if (wasPlaying) player.play();
        }
    }

    private String chainOrderText()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chainModules.size(); i++)
        {
            if (i > 0) sb.append(" → ");
            sb.append(chainModules.get(i).name);
        }
        return sb.toString();
    }

    /* =========================================================
     *  Dynamics panel (four automatic, "quick" compressors)
     * ========================================================= */

    /** Width of a card's gain-change meter, in pixels. */
    private static final double GR_METER_W = 120.0;

    /** Beat Comp card widgets that the BPM update touches. */
    private Label beatBpmLabel;
    private TextField beatBpmField;
    private CheckBox bpmAuto;

    /** Peak/Beat reduction knobs, whose range follows the detected maximum. */
    private Knob peakTargetKnob, beatTargetKnob;
    /** Remaining parameter controls, referenced so presets / undo can restore them. */
    private Knob levelingKnob, levelerSpeedKnob, punchKnob;
    private Knob satKnob, clipKnob;
    private ComboBox<Saturation.Algorithm> satAlgoCombo;
    private ComboBox<BeatCompProcessor.NoteValue> beatNoteCombo;
    private final Knob[] mbPushKnobs = new Knob[MultibandLimiterProcessor.BANDS];
    private Knob bbPushKnob;

    /** A compressor card (a square): processor + the widgets the UI updates. */
    private static final class DynCard
    {
        final AnalysisDynamicsProcessor proc;
        final String name;
        final CheckBox on;
        final Region grFill;
        final Label grLabel;
        final Label badge;     // chain position number (1..4)
        final VBox node;
        final double meterMaxDb;

        DynCard(AnalysisDynamicsProcessor proc, String name, CheckBox on, Region grFill,
                Label grLabel, Label badge, VBox node, double meterMaxDb)
        {
            this.proc = proc; this.name = name; this.on = on; this.grFill = grFill;
            this.grLabel = grLabel; this.badge = badge; this.node = node;
            this.meterMaxDb = meterMaxDb;
        }
    }

    /** Builds the four compressor cards and wires the master enable. */
    private void initDynamicsUi()
    {
        peakComp.setTrackAnalysis(trackAnalysis);
        beatComp.setTrackAnalysis(trackAnalysis);
        punch.setTrackAnalysis(trackAnalysis);

        dynCards.clear();
        dynCards.add(buildPeakCompCard());
        dynCards.add(buildBeatCompCard());
        dynCards.add(buildLevelerCard());
        dynCards.add(buildPunchCard());
        layoutDynamicsGrid();

        dynMasterEnabled.selectedProperty().addListener((o, ov, nv) ->
        {
            updateAllCompressorEnabled();
            scheduleDynamicsRefresh();
        });
        updateAllCompressorEnabled();
        updateBpmUi();
    }

    /**
     * Builds one square card: a header (a draggable position-badge + name grip,
     * and the on switch) over a centred control over the live gain meter. The
     * grip is the drag handle for reordering; the knob is free to drag its value.
     */
    private DynCard buildSquare(AnalysisDynamicsProcessor proc, String name, String tip,
                                String accentHex, double meterMaxDb, Node control)
    {
        Label badge = new Label("1");
        badge.getStyleClass().add("dyn-badge");
        badge.setStyle("-fx-background-color: " + accentHex + ";");

        Label title = new Label(name);
        title.getStyleClass().add("dyn-card-title");
        title.setStyle("-fx-text-fill: " + accentHex + ";");
        Tooltip tt = new Tooltip(tip);
        tt.setShowDelay(Duration.millis(250));
        tt.setShowDuration(Duration.INDEFINITE);
        tt.setHideDelay(Duration.millis(100));
        tt.setWrapText(true);
        tt.setMaxWidth(300);
        Tooltip.install(title, tt);

        // Grip = badge + name; this is the drag handle for reordering.
        HBox grip = new HBox(7, badge, title);
        grip.setAlignment(Pos.CENTER_LEFT);
        grip.setCursor(javafx.scene.Cursor.OPEN_HAND);
        Tooltip.install(grip, new Tooltip("Drag to reorder in the chain"));

        CheckBox on = new CheckBox("on");
        on.getStyleClass().add("enable-check");
        on.selectedProperty().addListener((o, ov, nv) ->
        {
            updateAllCompressorEnabled();
            scheduleDynamicsRefresh();
        });

        Region hsp = new Region();
        HBox.setHgrow(hsp, Priority.ALWAYS);
        HBox header = new HBox(7, grip, hsp, on);
        header.setAlignment(Pos.CENTER_LEFT);

        control.disableProperty().bind(dynMasterEnabled.selectedProperty().not());
        StackPane controlBox = new StackPane(control);
        controlBox.setAlignment(Pos.CENTER);
        VBox.setVgrow(controlBox, Priority.ALWAYS);

        // Live gain-change meter (- reduce / + boost) along the bottom.
        Region track = new Region();
        track.getStyleClass().add("gr-meter-track");
        track.setMinSize(GR_METER_W, 7);
        track.setPrefSize(GR_METER_W, 7);
        track.setMaxSize(GR_METER_W, 7);
        Region fill = new Region();
        fill.getStyleClass().add("gr-meter-fill");
        fill.setMaxHeight(7);
        fill.setPrefWidth(0);
        fill.setMaxWidth(0);
        StackPane meter = new StackPane(track, fill);
        meter.setMaxWidth(GR_METER_W);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        Label grLabel = new Label("0.0 dB");
        grLabel.getStyleClass().add("value");
        grLabel.setMinWidth(58);
        Label grCap = new Label("GR");
        grCap.getStyleClass().add("caption");
        grCap.setMinWidth(58);                 // match grLabel so the bar centres under the knob
        grCap.setAlignment(Pos.CENTER_RIGHT);
        HBox meterRow = new HBox(8, grCap, meter, grLabel);
        meterRow.setAlignment(Pos.CENTER);

        VBox square = new VBox(7, header, controlBox, meterRow);
        square.getStyleClass().add("dynamics-card");
        square.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);   // fill its grid cell

        installSquareDrag(grip, square, proc);
        return new DynCard(proc, name, on, fill, grLabel, badge, square, meterMaxDb);
    }

    /**
     * Wires reordering: a drag started on {@code dragSource} (the grip) can be
     * dropped on any {@code dropTarget} square. The control inside the square is
     * left free to handle its own drags.
     */
    private void installSquareDrag(Node dragSource, Node dropTarget, AudioProcessor proc)
    {
        dragSource.setOnDragDetected(ev ->
        {
            Dragboard db = dragSource.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(Integer.toString(dynamicsOrder.indexOf(proc)));
            db.setContent(cc);
            ev.consume();
        });
        dropTarget.setOnDragOver(ev ->
        {
            if (ev.getDragboard().hasString()) ev.acceptTransferModes(TransferMode.MOVE);
            ev.consume();
        });
        dropTarget.setOnDragDropped(ev ->
        {
            boolean ok = false;
            Dragboard db = ev.getDragboard();
            if (db.hasString())
            {
                moveDynamics(Integer.parseInt(db.getString()), dynamicsOrder.indexOf(proc));
                ok = true;
            }
            ev.setDropCompleted(ok);
            ev.consume();
        });
    }

    /** Places the four squares in the 2x2 grid in chain order and numbers them. */
    private void layoutDynamicsGrid()
    {
        // 3×3: card | divider | card  ×  card | divider | card - the blocks sit
        // directly on the base, separated by thin lines (no card boxes).
        if (dynamicsGrid.getColumnConstraints().size() != 3)
        {
            javafx.scene.layout.ColumnConstraints cg1 = new javafx.scene.layout.ColumnConstraints();
            cg1.setHgrow(Priority.ALWAYS);
            javafx.scene.layout.ColumnConstraints cgd = new javafx.scene.layout.ColumnConstraints(1);
            javafx.scene.layout.ColumnConstraints cg2 = new javafx.scene.layout.ColumnConstraints();
            cg2.setHgrow(Priority.ALWAYS);
            dynamicsGrid.getColumnConstraints().setAll(cg1, cgd, cg2);

            javafx.scene.layout.RowConstraints rg1 = new javafx.scene.layout.RowConstraints();
            rg1.setVgrow(Priority.ALWAYS);
            javafx.scene.layout.RowConstraints rgd = new javafx.scene.layout.RowConstraints(1);
            javafx.scene.layout.RowConstraints rg2 = new javafx.scene.layout.RowConstraints();
            rg2.setVgrow(Priority.ALWAYS);
            dynamicsGrid.getRowConstraints().setAll(rg1, rgd, rg2);
        }

        dynamicsGrid.getChildren().clear();
        for (int i = 0; i < dynamicsOrder.size(); i++)
        {
            DynCard c = cardFor(dynamicsOrder.get(i));
            if (c == null) continue;
            c.badge.setText(Integer.toString(i + 1));
            GridPane.setColumnIndex(c.node, (i % 2) * 2);
            GridPane.setRowIndex(c.node, (i / 2) * 2);
            dynamicsGrid.getChildren().add(c.node);
        }

        Region vDiv = new Region();
        vDiv.setStyle("-fx-background-color: #2f2f37;");
        vDiv.setMinWidth(1); vDiv.setMaxWidth(1); vDiv.setMaxHeight(Double.MAX_VALUE);
        GridPane.setColumnIndex(vDiv, 1); GridPane.setRowIndex(vDiv, 0); GridPane.setRowSpan(vDiv, 3);
        Region hDiv = new Region();
        hDiv.setStyle("-fx-background-color: #2f2f37;");
        hDiv.setMinHeight(1); hDiv.setMaxHeight(1); hDiv.setMaxWidth(Double.MAX_VALUE);
        GridPane.setColumnIndex(hDiv, 0); GridPane.setRowIndex(hDiv, 1); GridPane.setColumnSpan(hDiv, 3);
        dynamicsGrid.getChildren().addAll(vDiv, hDiv);
    }

    private DynCard cardFor(AudioProcessor p)
    {
        for (DynCard c : dynCards) if (c.proc == p) return c;
        return null;
    }

    /**
     * Moves a compressor to a new chain position, then recomputes the chain and
     * resumes playback from where it was.
     */
    private void moveDynamics(int from, int to)
    {
        if (from == to || from < 0 || to < 0
                || from >= dynamicsOrder.size() || to >= dynamicsOrder.size()) return;
        AudioProcessor moved = dynamicsOrder.remove(from);
        dynamicsOrder.add(to, moved);
        layoutDynamicsGrid();

        boolean wasPlaying = player.isPlaying();
        int sr = (loadedFile != null) ? loadedFile.getSampleRate() : 0;
        double posSec = (sr > 0) ? player.getPositionSamples() / (double) sr : 0.0;
        player.stop();

        List<AudioProcessor> order = new ArrayList<>();
        for (ChainModule m : chainModules) order.addAll(m.processors);
        order.add(normalizer);   // Peak Normalizer pinned last
        pipeline.setProcessors(order);

        setStatus("Recomputing dynamics…");
        syncLiveAnalysis(() ->
        {
            if (loadedFile != null && posSec > 0.0) player.seekTo(posSec);
            if (wasPlaying)
            {
                player.play();   // syncLiveAnalysis above already marked the analysis valid
            }
            setStatus("Dynamics order: " + dynamicsOrderText());
        });
    }

    private Knob dynKnob(String label, double min, double max, double init,
                         String accent, Knob.Formatter fmt, String tip,
                         java.util.function.DoubleConsumer setter)
    {
        Knob k = new Knob(label, min, max, init);
        k.scale(1.7).accent(accent).formatter(fmt).tooltip(tip);
        k.valueProperty().addListener((o, ov, nv) -> { setter.accept(nv.doubleValue()); scheduleDynamicsRefresh(); });
        return k;
    }

    private DynCard buildPeakCompCard()
    {
        peakTargetKnob = dynKnob("Max Peak Reduction", PeakCompProcessor.MIN_TARGET_DB, PeakCompProcessor.MAX_TARGET_DB,
                peakComp.getTargetDb(), "#ffb454", v -> String.format(Locale.US, "%.1f dB", v),
                "Compresses only the loudest transients in the track. The knob sets the maximum amount, in dB, shaved off the peaks.",
                peakComp::setTargetDb);
        return buildSquare(peakComp, "Peak Comp",
                "Compresses only the loudest transients to reduce dynamic range and gain loudness.",
                "#ffb454", 12.0, peakTargetKnob);
    }

    private DynCard buildBeatCompCard()
    {
        beatTargetKnob = dynKnob("Max Beat Reduction", BeatCompProcessor.MIN_TARGET_DB, BeatCompProcessor.MAX_TARGET_DB,
                beatComp.getTargetDb(), "#4a9eff", v -> String.format(Locale.US, "%.1f dB", v),
                "Applies a glue-style compression that evens out the volume of every beat.",
                beatComp::setTargetDb);

        ComboBox<BeatCompProcessor.NoteValue> noteCombo = new ComboBox<>();
        noteCombo.getItems().setAll(BeatCompProcessor.NoteValue.values());
        noteCombo.getSelectionModel().select(beatComp.getNote());
        noteCombo.setPrefWidth(72);
        noteCombo.valueProperty().addListener((o, ov, nv) ->
        {
            if (nv != null) { beatComp.setNote(nv); updateBpmUi(); scheduleDynamicsRefresh(); }
        });
        beatNoteCombo = noteCombo;

        beatBpmLabel = new Label("no tempo");
        beatBpmLabel.getStyleClass().add("value-muted");
        beatBpmLabel.setWrapText(true);

        bpmAuto = new CheckBox("Auto");
        bpmAuto.getStyleClass().add("enable-check");
        bpmAuto.setSelected(true);
        beatBpmField = new TextField();
        beatBpmField.setPromptText("BPM");
        beatBpmField.setPrefWidth(58);
        beatBpmField.setDisable(true);                 // editable only in manual
        beatBpmField.setOnAction(e -> commitManualBpm());
        bpmAuto.selectedProperty().addListener((o, ov, on) ->
        {
            if (on) trackAnalysis.clearManualBpm();
            else if (trackAnalysis.getBpm() > 0) trackAnalysis.setManualBpm(trackAnalysis.getBpm());
            updateBpmUi();
            scheduleDynamicsRefresh();
        });

        Label relCap = new Label("Release"); relCap.getStyleClass().add("caption");
        VBox right = new VBox(5, new HBox(6, relCap, noteCombo),
                new HBox(6, bpmAuto, beatBpmField), beatBpmLabel);
        right.setAlignment(Pos.CENTER_LEFT);
        right.setMaxWidth(150);

        HBox controls = new HBox(14, beatTargetKnob, right);
        controls.setAlignment(Pos.CENTER);
        return buildSquare(beatComp, "Beat Comp",
                "Levels the volume of transients across beats (release synced to the tempo).",
                "#4a9eff", 12.0, controls);
    }

    private DynCard buildLevelerCard()
    {
        Knob lev = dynKnob("Leveling", LevelerProcessor.MIN_LEVELING, LevelerProcessor.MAX_LEVELING,
                leveler.getLeveling(), "#54d98c", v -> String.format(Locale.US, "%.0f %%", v * 100),
                "How strongly to match the loudness of the song's sections (100% = no difference between parts).",
                leveler::setLeveling);
        Knob speed = dynKnob("Speed", LevelerProcessor.MIN_SPEED, LevelerProcessor.MAX_SPEED,
                leveler.getSpeed(), "#54d98c", v -> String.format(Locale.US, "%.0f %%", v * 100),
                "How fast the leveler follows section changes (low = slow and gentle, high = agile)",
                leveler::setSpeed);
        levelingKnob = lev;
        levelerSpeedKnob = speed;
        HBox knobs = new HBox(14, lev, speed);
        knobs.setAlignment(Pos.CENTER);
        return buildSquare(leveler, "Leveler",
                "Reduces the volume difference between sections of the song so the master is consistent.",
                "#54d98c", 12.0, knobs);
    }

    private DynCard buildPunchCard()
    {
        Knob k = dynKnob("Amount", PunchProcessor.MIN_AMOUNT_DB, PunchProcessor.MAX_AMOUNT_DB,
                punch.getAmountDb(), "#c77dff", v -> String.format(Locale.US, "%.1f dB", v),
                "How much to boost the transients above the body.",
                punch::setAmountDb);
        punchKnob = k;
        return buildSquare(punch, "Punch",
                "Adds punch through transient expansion (boosts transients, leaves the body untouched).",
                "#c77dff", 12.0, k);
    }

    /** Applies the master enable + each card's own toggle to its compressor. */
    private void updateAllCompressorEnabled()
    {
        boolean master = dynMasterEnabled.isSelected();
        for (DynCard c : dynCards)
        {
            c.proc.setEnabled(master && c.on.isSelected());
        }
    }

    private String dynamicsOrderText()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dynamicsOrder.size(); i++)
        {
            if (i > 0) sb.append(" → ");
            DynCard c = cardFor(dynamicsOrder.get(i));
            sb.append(c != null ? c.name : "?");
        }
        return sb.toString();
    }

    /** Reads the typed BPM, applies it as a manual override and refreshes Beat Comp. */
    private void commitManualBpm()
    {
        if (beatBpmField == null) return;
        try
        {
            double v = Double.parseDouble(beatBpmField.getText().trim());
            if (v > 0)
            {
                trackAnalysis.setManualBpm(v);
                updateBpmUi();
                scheduleDynamicsRefresh();
            }
        }
        catch (NumberFormatException ignored) { updateBpmUi(); }
    }

    /** Refreshes the BPM read-outs (Beat Comp card + top bar) from {@link #trackAnalysis}. */
    private void updateBpmUi()
    {
        double det = trackAnalysis.getDetectedBpm();
        if (topBpmLabel != null)
            topBpmLabel.setText(det > 0 ? String.format(Locale.US, "· %.0f BPM", det) : "");

        if (beatBpmLabel == null) return;
        double bpm = trackAnalysis.getBpm();
        if (bpm <= 0.0)
        {
            beatBpmLabel.setText("no tempo");
        }
        else
        {
            String src = trackAnalysis.isManualBpm() ? "manual" : "auto";
            beatBpmLabel.setText(String.format(Locale.US, "%.0f BPM · %s · %.0f ms",
                    bpm, src, beatComp.getReleaseMs()));
        }
        if (beatBpmField != null && !beatBpmField.isFocused())
            beatBpmField.setText(bpm > 0 ? String.format(Locale.US, "%.0f", bpm) : "");
        if (bpmAuto != null && beatBpmField != null)
            beatBpmField.setDisable(bpmAuto.isSelected());
    }

    /** Updates each card's -GR meter (called from the playback animator). */
    private void updateDynamicsMeters()
    {
        for (DynCard c : dynCards)
        {
            double gr = c.proc.getGainReductionDb();          // signed: - reduce, + boost
            double mag = Math.min(Math.abs(gr), c.meterMaxDb);
            double w = (c.meterMaxDb > 0) ? mag / c.meterMaxDb : 0.0;
            c.grFill.setPrefWidth(w * GR_METER_W);
            c.grFill.setMaxWidth(w * GR_METER_W);
            c.grLabel.setText(String.format(Locale.US, "%+.1f dB", gr));
        }
    }

    /** Clears the -GR meters (called when playback stops). */
    private void resetDynamicsMeters()
    {
        for (DynCard c : dynCards)
        {
            c.grFill.setPrefWidth(0);
            c.grFill.setMaxWidth(0);
            c.grLabel.setText("0.0 dB");
        }
    }

    /* =========================================================
     *  Clip section: Soft-Clip (saturation) + Hard-Clip
     * ========================================================= */

    private static final class ClipCard
    {
        final CheckBox on;
        final Region grFill;
        final Label grLabel;
        final java.util.function.LongToDoubleFunction grAt;   // gain reduction at a base-rate play position
        final java.util.function.Consumer<Boolean> setEnabled;
        final double meterMaxDb;

        ClipCard(CheckBox on, Region grFill, Label grLabel,
                 java.util.function.LongToDoubleFunction grAt,
                 java.util.function.Consumer<Boolean> setEnabled, double meterMaxDb)
        {
            this.on = on; this.grFill = grFill; this.grLabel = grLabel;
            this.grAt = grAt; this.setEnabled = setEnabled; this.meterMaxDb = meterMaxDb;
        }
    }

    private final List<ClipCard> clipCardList = new ArrayList<>();

    /** Builds the two clip cards (Soft-Clip + Hard-Clip) and wires the master enable. */
    private void initClipUi()
    {
        if (clipCards == null) return;
        clipCardList.clear();

        satKnob = new Knob("Saturate", SoftClipProcessor.MIN_SAT_DB, SoftClipProcessor.MAX_SAT_DB,
                SoftClipProcessor.DEFAULT_SAT_DB)
                .formatter(v -> v < 0.05 ? "0.0 dB" : String.format(Locale.US, "-%.1f dB", v))
                .accent("#b58cf0").scale(2.1)
                .tooltip("Sets the amount of peak reduction reached through saturation, in dB. Soft-Clip has a wider knee, so it also generates harmonics and colours the sound.");
        satKnob.valueProperty().addListener((o, ov, nv) ->
        {
            softClip.setSatDb(nv.doubleValue());
            scheduleDynamicsRefresh();
        });
        ComboBox<Saturation.Algorithm> satAlgo = new ComboBox<>();
        satAlgo.getItems().setAll(Saturation.Algorithm.TUBE, Saturation.Algorithm.TAPE,
                Saturation.Algorithm.TRANSFORMER);
        satAlgo.setConverter(prettyEnumConverter());
        satAlgo.getSelectionModel().select(softClip.getAlgorithm());
        satAlgo.setPrefWidth(132);
        satAlgo.valueProperty().addListener((o, ov, nv) ->
        {
            if (nv != null) { softClip.setAlgorithm(nv); scheduleDynamicsRefresh(); }
        });
        satAlgoCombo = satAlgo;
        VBox softCard = buildClipCard("Soft-Clip", "#b58cf0", satKnob, satAlgo,
                softClip::getGrAtPosition, softClip::setEnabled);

        clipKnob = new Knob("Clip", HardClipProcessor.MIN_CLIP_DB, HardClipProcessor.MAX_CLIP_DB,
                HardClipProcessor.DEFAULT_CLIP_DB)
                .formatter(v -> v < 0.05 ? "0.0 dB" : String.format(Locale.US, "-%.1f dB", v))
                .accent("#4a9eff").scale(2.1)
                .tooltip("Exact dB the loudest peak is clipped down by.");
        clipKnob.valueProperty().addListener((o, ov, nv) ->
        {
            hardClip.setClipDb(nv.doubleValue());
            scheduleDynamicsRefresh();
        });
        VBox hardCard = buildClipCard("Hard-Clip", "#4a9eff", clipKnob, null,
                hardClip::getGrAtPosition, hardClip::setEnabled);

        HBox.setHgrow(softCard, Priority.ALWAYS);
        HBox.setHgrow(hardCard, Priority.ALWAYS);
        Region clipDivider = new Region();
        clipDivider.setMinWidth(1);
        clipDivider.setMaxWidth(1);
        clipDivider.setStyle("-fx-background-color: #2f2f37;");
        clipCards.getChildren().setAll(softCard, clipDivider, hardCard);

        clipEnabled.selectedProperty().addListener((o, ov, nv) ->
        {
            updateClipEnabled();
            scheduleDynamicsRefresh();
        });
        updateClipEnabled();
    }

    private VBox buildClipCard(String name, String accentHex, Node knob, Node selector,
                               java.util.function.LongToDoubleFunction grAt,
                               java.util.function.Consumer<Boolean> setEnabled)
    {
        Label title = new Label(name);
        title.getStyleClass().add("dyn-card-title");
        title.setStyle("-fx-text-fill: " + accentHex + "; -fx-font-size: 14px;");
        CheckBox on = new CheckBox("on");
        on.getStyleClass().add("enable-check");
        on.selectedProperty().addListener((o, ov, nv) ->
        {
            updateClipEnabled();
            scheduleDynamicsRefresh();
        });
        Region hsp = new Region();
        HBox.setHgrow(hsp, Priority.ALWAYS);
        HBox header = new HBox(7, title, hsp, on);
        header.setAlignment(Pos.CENTER_LEFT);

        knob.disableProperty().bind(clipEnabled.selectedProperty().not());
        if (selector != null) selector.disableProperty().bind(clipEnabled.selectedProperty().not());

        StackPane controlBox = new StackPane(knob);
        controlBox.setAlignment(Pos.CENTER);
        VBox.setVgrow(controlBox, Priority.ALWAYS);

        Region track = new Region();
        track.getStyleClass().add("gr-meter-track");
        track.setMinSize(GR_METER_W, 7);
        track.setPrefSize(GR_METER_W, 7);
        track.setMaxSize(GR_METER_W, 7);
        Region fill = new Region();
        fill.getStyleClass().add("gr-meter-fill");
        fill.setMaxHeight(7);
        fill.setPrefWidth(0);
        fill.setMaxWidth(0);
        StackPane meter = new StackPane(track, fill);
        meter.setMaxWidth(GR_METER_W);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        Label grLabel = new Label("0.0 dB");
        grLabel.getStyleClass().add("value");
        grLabel.setMinWidth(58);
        Label grCap = new Label("GR");
        grCap.getStyleClass().add("caption");
        grCap.setMinWidth(58);
        grCap.setAlignment(Pos.CENTER_RIGHT);
        HBox meterRow = new HBox(8, grCap, meter, grLabel);
        meterRow.setAlignment(Pos.CENTER);

        VBox card;
        if (selector != null)
        {
            HBox selectorRow = new HBox(selector);
            selectorRow.setAlignment(Pos.CENTER);
            card = new VBox(14, header, controlBox, selectorRow, meterRow);
        }
        else
        {
            card = new VBox(14, header, controlBox, meterRow);
        }
        card.setPadding(new javafx.geometry.Insets(16, 22, 18, 22));
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        clipCardList.add(new ClipCard(on, fill, grLabel, grAt, setEnabled, 6.0));
        return card;
    }

    private void updateClipEnabled()
    {
        boolean master = clipEnabled.isSelected();
        for (ClipCard c : clipCardList) c.setEnabled.accept(master && c.on.isSelected());
    }

    private void updateClipMeters()
    {
        long pos = (player != null) ? player.getPositionSamples() : 0L;
        for (ClipCard c : clipCardList)
        {
            double gr = c.grAt.applyAsDouble(pos);
            double mag = Math.min(Math.abs(gr), c.meterMaxDb);
            double w = (c.meterMaxDb > 0) ? mag / c.meterMaxDb : 0.0;
            c.grFill.setPrefWidth(w * GR_METER_W);
            c.grFill.setMaxWidth(w * GR_METER_W);
            c.grLabel.setText(String.format(Locale.US, "%+.1f dB", gr));
        }
    }

    private void resetClipMeters()
    {
        for (ClipCard c : clipCardList)
        {
            c.grFill.setPrefWidth(0);
            c.grFill.setMaxWidth(0);
            c.grLabel.setText("0.0 dB");
        }
    }

    /**
     * Recomputes the per-track tempo + onset analysis on a background task
     * (it drives Glue's tempo and Punch's transient map). Runs on load and
     * after any destructive edit (crop / delete / undo) so the Dynamics
     * section always reflects the current audio. {@code after} runs on the FX
     * thread when done.
     */
    private void recomputeTrackAnalysis(Runnable after)
    {
        if (loadedFile == null) { if (after != null) after.run(); return; }
        final float[] src = loadedFile.getSamples().clone();
        final int sr = loadedFile.getSampleRate();
        final int ch = loadedFile.getChannels();
        Task<Void> task = new Task<>()
        {
            @Override protected Void call()
            {
                trackAnalysis.analyze(src, ch, sr);
                spectrumAnalysis.analyze(src, ch, sr);
                return null;
            }
        };
        analyzing(true);
        task.setOnSucceeded(e ->
        {
            analyzing(false);
            updateBpmUi();
            drawEqCurve();              // show the freshly analysed spectrum
            syncLiveAnalysis();
            if (after != null) after.run();
        });
        task.setOnFailed(e -> { analyzing(false); if (after != null) after.run(); });
        runTask(task);
    }

    /* =========================================================
     *  Live re-analysis of the dynamics chain
     * ========================================================= */

    private PauseTransition dynRefreshDebounce;

    /**
     * Schedules a chain re-analysis ~220&nbsp;ms after the last parameter
     * change. The first call of a gesture also captures the chain state, so
     * the whole gesture lands on the undo history as one entry when the
     * debounce fires.
     */
    private void scheduleDynamicsRefresh()
    {
        if (applyingPreset) return;
        if (loadedFile == null) return;
        if (paramGestureBaseline == null)
        {
            paramGestureBaseline = capturePreset();
        }
        if (dynRefreshDebounce == null)
        {
            dynRefreshDebounce = new PauseTransition(Duration.millis(220));
            dynRefreshDebounce.setOnFinished(e ->
            {
                commitParamGesture();
                syncLiveAnalysis();
            });
        }
        dynRefreshDebounce.playFromStart();
    }

    /** Pushes the open parameter gesture (if it changed anything) onto the undo history. */
    private void commitParamGesture()
    {
        com.quickmaster.config.ChainPreset baseline = paramGestureBaseline;
        paramGestureBaseline = null;
        if (baseline == null) return;
        com.google.gson.Gson gson = new com.google.gson.Gson();
        if (gson.toJson(baseline).equals(gson.toJson(capturePreset()))) return;   // no-op gesture
        undoStack.push(new EditState(null, baseline));
        trimUndoHistory();
        redoStack.clear();
        updateUndoRedoButtons();
    }

    /**
     * Re-analyses the chain on a background snapshot in ONE cumulative pass and
     * copies the result onto the live processors: each compressor receives its
     * new gain envelope and the Peak Normalizer its new peak. The same render
     * feeds the output meters (LUFS, LRA, true peak, correlation), and the
     * post-EQ signal is cached so a dynamics-only gesture skips re-rendering
     * the tonal stages. {@code onDone} runs on the FX thread when done.
     */
    private void syncLiveAnalysis() { syncLiveAnalysis(null); }

    private void syncLiveAnalysis(Runnable onDone)
    {
        if (loadedFile == null) { if (onDone != null) onDone.run(); return; }
        final float[] src = loadedFile.getSamples().clone();
        final int sr = loadedFile.getSampleRate();
        final int ch = loadedFile.getChannels();
        final Snapshot s = buildSnapshot();

        // Tonal-prefix cache: when the EQ block (and the source) are unchanged,
        // start the pass from the cached post-EQ signal.
        final int tonalStages = tonalStageCount(s);
        final long sig = tonalSignature(src, sr, ch, tonalStages);
        final boolean useCache = tonalStages > 0
                && tonalStagesCache == tonalStages
                && tonalSigCache == sig
                && tonalBufCache != null
                && tonalBufCache.length == src.length;
        final float[] startBuf = useCache ? tonalBufCache : null;
        final float[][] tonalTap = { null };

        Task<double[]> task = new Task<>()
        {
            @Override protected double[] call()
            {
                s.pipeline.prepare(sr, src.length);
                float[] render = s.pipeline.analyzeAndRender(src, ch,
                        useCache ? tonalStages : 0, startBuf, null,
                        (buf, idx) -> { if (idx == tonalStages - 1) tonalTap[0] = buf; });

                // The render doubles as the output measurement.
                LoudnessMeter meter = new LoudnessMeter();
                meter.prepare(sr);
                meter.process(render, ch);
                double tp = com.dspark.analysis.TruePeak.measureMax(render, ch);
                double tpDb = (tp <= 0.0) ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(tp);
                double corr = correlationOf(render, ch);
                return new double[] {
                        meter.getIntegratedLufs(), meter.getShortTermLufs(),
                        meter.getMomentaryLufs(), meter.getLoudnessRange(),
                        tpDb, s.normalizer.getGainDb(), corr };
            }
        };
        analyzing(true);
        task.setOnSucceeded(e ->
        {
            analyzing(false);
            if (!useCache && s.copies.get(autoEq) instanceof AutoEqProcessor a) autoEq.adopt(a);
            adoptLive(peakComp, s);
            adoptLive(beatComp, s);
            adoptLive(leveler, s);
            adoptLive(punch, s);
            if (s.copies.get(softClip) instanceof SoftClipProcessor sc) softClip.adoptAnalysis(sc);
            if (s.copies.get(hardClip) instanceof HardClipProcessor hc) hardClip.adoptAnalysis(hc);
            if (s.copies.get(multiband) instanceof MultibandLimiterProcessor mb) multiband.adoptAnalysis(mb);
            if (s.copies.get(broadband) instanceof BroadbandLimiterProcessor bb) broadband.adoptAnalysis(bb);
            normalizer.setAnalyzedPeak(s.normalizer.getAnalyzedPeak());
            peakAppliedLabel.setText(normalizer.isEnabled()
                    ? formatSignedDb(normalizer.getGainDb()) : "·");
            updateTargetRanges(s);
            player.setAnalysisValid(true);   // live chain is now analysed: the next play reuses it

            if (tonalTap[0] != null)
            {
                tonalBufCache = tonalTap[0];
                tonalSigCache = sig;
                tonalStagesCache = tonalStages;
            }

            // Output meters from the same render (live meters take over while playing).
            double[] r = task.getValue();
            if (!player.isPlaying())
            {
                meterLufs.setText(formatLufs(r[0]));
                meterShort.setText(formatLufs(r[1]));
                meterMom.setText(formatLufs(r[2]));
                meterLra.setText(String.format(Locale.US, "%.1f LU", r[3]));
                meterPeak.setText(Double.isInfinite(r[4]) ? "-∞ dBTP"
                        : String.format(Locale.US, "%.1f dBTP", r[4]));
                if (meterCorr != null) meterCorr.setText(String.format(Locale.US, "%+.2f", r[6]));
            }
            if (onDone != null) onDone.run();
        });
        task.setOnFailed(e -> { AppLogger.error("Live analysis failed.", task.getException()); analyzing(false); if (onDone != null) onDone.run(); });
        runTask(task);
    }

    /** Number of leading snapshot stages that belong to the tonal (EQ) block. */
    private int tonalStageCount(Snapshot s)
    {
        int n = 0;
        for (AudioProcessor p : s.pipeline.getProcessors())
        {
            if (p instanceof AutoEqProcessor || p instanceof EqualizerProcessor
                    || p instanceof FadeProcessor) n++;
            else break;
        }
        return n;
    }

    /** Cache key for the tonal prefix: source identity + every tonal parameter. */
    private long tonalSignature(float[] src, int sr, int ch, int tonalStages)
    {
        long h = 1125899906842597L;
        h = h * 31 + tonalStages;
        h = h * 31 + sr;
        h = h * 31 + ch;
        h = h * 31 + src.length;
        int stride = Math.max(1, src.length / 512);
        for (int i = 0; i < src.length; i += stride)
            h = h * 1099511628211L + Float.floatToIntBits(src[i]);

        h = h * 31 + (autoEq.isEnabled() ? 1 : 0);
        h = h * 31 + Double.hashCode(autoEq.getAmount());
        h = h * 31 + autoEq.getTarget().ordinal();
        h = h * 31 + Double.hashCode(autoEq.getAttackSec());
        h = h * 31 + Double.hashCode(autoEq.getReleaseSec());

        h = h * 31 + (eq.isEnabled() ? 1 : 0);
        h = h * 31 + eq.getNumBands();
        for (int i = 0; i < eq.getNumBands(); i++)
        {
            MasterEqualizer.Band b = eq.getBand(i);
            if (b == null) continue;
            h = h * 31 + b.type.ordinal();
            h = h * 31 + b.channel.ordinal();
            h = h * 31 + b.phase.ordinal();
            h = h * 31 + Double.hashCode(b.frequency);
            h = h * 31 + Double.hashCode(b.gainDb);
            h = h * 31 + Double.hashCode(b.q);
            h = h * 31 + b.slope;
            h = h * 31 + (b.enabled ? 1 : 0);
            h = h * 31 + (b.dynamic ? 1 : 0);
            h = h * 31 + Double.hashCode(b.threshold);
            h = h * 31 + Double.hashCode(b.aboveRatio);
            h = h * 31 + Double.hashCode(b.aboveRangeDb);
            h = h * 31 + Double.hashCode(b.aboveAttackMs);
            h = h * 31 + Double.hashCode(b.aboveReleaseMs);
            h = h * 31 + (b.aboveBoost ? 1 : 0);
            h = h * 31 + Double.hashCode(b.belowRatio);
            h = h * 31 + Double.hashCode(b.belowRangeDb);
            h = h * 31 + Double.hashCode(b.belowAttackMs);
            h = h * 31 + Double.hashCode(b.belowReleaseMs);
            h = h * 31 + (b.belowBoost ? 1 : 0);
        }

        h = h * 31 + (fade.isEnabled() ? 1 : 0);
        h = h * 31 + Double.hashCode(fade.getFadeInSec());
        h = h * 31 + Double.hashCode(fade.getFadeOutSec());
        h = h * 31 + fade.getFadeType().ordinal();
        return h;
    }

    private void adoptLive(AnalysisDynamicsProcessor live, Snapshot s)
    {
        AudioProcessor copy = s.copies.get(live);
        if (copy instanceof AnalysisDynamicsProcessor adc) live.adoptEnvelope(adc);
    }

    /** Sets the Peak/Beat target dials' minimum to the real maximum reduction found. */
    private void updateTargetRanges(Snapshot s)
    {
        if (s.copies.get(peakComp) instanceof PeakCompProcessor p)
            applyTargetRange(peakTargetKnob, p.getMaxReductionDb());
        if (s.copies.get(beatComp) instanceof BeatCompProcessor b)
            applyTargetRange(beatTargetKnob, b.getMaxExcessDb());
    }

    private void applyTargetRange(Knob knob, double maxOvershoot)
    {
        if (knob == null) return;
        double min = (maxOvershoot >= 1.0) ? -Math.round(maxOvershoot) : -1.0;
        if (Math.abs(knob.getMin() - min) > 0.4) knob.range(min, 0.0);
    }

    /** A throwaway clone of the live chain + a map from each live stage to its copy. */
    private static final class Snapshot
    {
        final ProcessingPipeline pipeline;
        final java.util.Map<AudioProcessor, AudioProcessor> copies;
        final PeakNormalizer normalizer;
        Snapshot(ProcessingPipeline p, java.util.Map<AudioProcessor, AudioProcessor> c, PeakNormalizer n)
        { this.pipeline = p; this.copies = c; this.normalizer = n; }
    }

    /**
     * Builds an independent copy of the current chain, in the live chain order
     * with the Peak Normalizer and Limiter pinned at the end.
     */
    private Snapshot buildSnapshot()
    {
        return buildSnapshot(trackAnalysis);
    }

    /**
     * Like {@link #buildSnapshot()} but bound to a specific per-track analysis,
     * so the batch export can master each file with its own tempo and onsets.
     */
    private Snapshot buildSnapshot(TrackAnalysis analysis)
    {
        AutoEqProcessor oAutoEq = new AutoEqProcessor();
        oAutoEq.setEnabled(autoEq.isEnabled());
        oAutoEq.setAmount(autoEq.getAmount());
        oAutoEq.setTarget(autoEq.getTarget());
        oAutoEq.setAttackSec(autoEq.getAttackSec());
        oAutoEq.setReleaseSec(autoEq.getReleaseSec());
        oAutoEq.adopt(autoEq);   // reuse the current render; re-renders only if its input/params changed

        EqualizerProcessor oeq = new EqualizerProcessor();
        oeq.setNumBands(eq.getNumBands());
        for (int i = 0; i < eq.getNumBands(); i++) oeq.setBand(i, eq.getBand(i));
        oeq.setEnabled(eq.isEnabled());
        FadeProcessor ofade = new FadeProcessor();
        ofade.setFadeInSec(fade.getFadeInSec());
        ofade.setFadeOutSec(fade.getFadeOutSec());
        ofade.setEnabled(fade.isEnabled());

        PeakCompProcessor opeak = new PeakCompProcessor();
        opeak.setTrackAnalysis(analysis);
        opeak.setTargetDb(peakComp.getTargetDb());
        opeak.setEnabled(peakComp.isEnabled());
        BeatCompProcessor obeat = new BeatCompProcessor();
        obeat.setTrackAnalysis(analysis);
        obeat.setTargetDb(beatComp.getTargetDb());
        obeat.setNote(beatComp.getNote());
        obeat.setEnabled(beatComp.isEnabled());
        LevelerProcessor olev = new LevelerProcessor();
        olev.setLeveling(leveler.getLeveling());
        olev.setSpeed(leveler.getSpeed());
        olev.setEnabled(leveler.isEnabled());
        PunchProcessor opunch = new PunchProcessor();
        opunch.setTrackAnalysis(analysis);
        opunch.setAmountDb(punch.getAmountDb());
        opunch.setEnabled(punch.isEnabled());

        SoftClipProcessor osoft = new SoftClipProcessor();
        osoft.setSatDb(softClip.getSatDb());
        osoft.setAlgorithm(softClip.getAlgorithm());
        osoft.setEnabled(softClip.isEnabled());
        HardClipProcessor ohard = new HardClipProcessor();
        ohard.setClipDb(hardClip.getClipDb());
        ohard.setCurve(hardClip.getCurve());
        ohard.setEnabled(hardClip.isEnabled());

        PeakNormalizer onorm = new PeakNormalizer();
        onorm.setTargetDbfs(normalizer.getTargetDbfs());
        onorm.setEnabled(normalizer.isEnabled());
        MultibandLimiterProcessor omulti = new MultibandLimiterProcessor();
        for (int b = 0; b < MultibandLimiterProcessor.BANDS; b++) omulti.setPushDb(b, multiband.getPushDb(b));
        omulti.setEnabled(multiband.isEnabled());
        BroadbandLimiterProcessor obroad = new BroadbandLimiterProcessor();
        obroad.setPushDb(broadband.getPushDb());
        obroad.setEnabled(broadband.isEnabled());

        java.util.Map<AudioProcessor, AudioProcessor> copies = new java.util.IdentityHashMap<>();
        copies.put(autoEq, oAutoEq);
        copies.put(eq, oeq);     copies.put(fade, ofade);
        copies.put(peakComp, opeak); copies.put(beatComp, obeat);
        copies.put(leveler, olev);   copies.put(punch, opunch);
        copies.put(softClip, osoft); copies.put(hardClip, ohard);
        copies.put(multiband, omulti); copies.put(broadband, obroad);

        // Mirror the live chain order, with the Peak Normalizer pinned last.
        java.util.List<AudioProcessor> snapOrder = new ArrayList<>();
        for (ChainModule m : chainModules)
            for (AudioProcessor proc : m.processors)
            {
                AudioProcessor p = copies.get(proc);
                if (p != null) snapOrder.add(p);
            }
        snapOrder.add(onorm);

        ProcessingPipeline snap = new ProcessingPipeline();
        for (AudioProcessor p : snapOrder) snap.addProcessor(p);
        return new Snapshot(snap, copies, onorm);
    }

    /* =========================================================
     *  Chain presets: capture / apply, save / load, A/B settings
     * ========================================================= */

    /** Short key identifying a dynamics compressor in a preset. */
    private String procKey(AudioProcessor p)
    {
        if (p == peakComp) return "peak";
        if (p == beatComp) return "beat";
        if (p == leveler)  return "leveler";
        return "punch";
    }

    private AudioProcessor procForKey(String key)
    {
        switch (key)
        {
            case "peak":    return peakComp;
            case "beat":    return beatComp;
            case "leveler": return leveler;
            default:        return punch;
        }
    }

    /** Captures the entire chain configuration as a serializable preset. */
    private com.quickmaster.config.ChainPreset capturePreset()
    {
        com.quickmaster.config.ChainPreset p = new com.quickmaster.config.ChainPreset();

        p.autoEqOn = autoEq.isEnabled();
        p.autoEqAmount = autoEq.getAmount();
        p.autoEqTarget = autoEq.getTarget().name();
        p.autoEqAttackSec = autoEq.getAttackSec();
        p.autoEqReleaseSec = autoEq.getReleaseSec();

        p.eqOn = eq.isEnabled();
        for (int i = 0; i < eqBandCount; i++)
        {
            MasterEqualizer.Band b = eq.getBand(i);
            if (b == null) continue;
            com.quickmaster.config.ChainPreset.BandPreset bp =
                    new com.quickmaster.config.ChainPreset.BandPreset();
            bp.type = b.type.name();
            bp.channel = b.channel.name();
            bp.phase = b.phase.name();
            bp.frequency = b.frequency;
            bp.gainDb = b.gainDb;
            bp.q = b.q;
            bp.slope = b.slope;
            bp.enabled = b.enabled;
            bp.dynamic = b.dynamic;
            bp.threshold = b.threshold;
            bp.aboveRatio = b.aboveRatio;
            bp.aboveAttackMs = b.aboveAttackMs;
            bp.aboveReleaseMs = b.aboveReleaseMs;
            bp.aboveRangeDb = b.aboveRangeDb;
            bp.aboveBoost = b.aboveBoost;
            bp.belowRatio = b.belowRatio;
            bp.belowAttackMs = b.belowAttackMs;
            bp.belowReleaseMs = b.belowReleaseMs;
            bp.belowRangeDb = b.belowRangeDb;
            bp.belowBoost = b.belowBoost;
            p.bands.add(bp);
        }

        p.fadeInSec = fade.getFadeInSec();
        p.fadeOutSec = fade.getFadeOutSec();
        p.fadeType = fade.getFadeType().name();

        p.dynamicsOn = dynMasterEnabled.isSelected();
        DynCard pc = cardFor(peakComp), bc = cardFor(beatComp),
                lc = cardFor(leveler), uc = cardFor(punch);
        p.peakCompOn = pc != null && pc.on.isSelected();
        p.peakCompTargetDb = peakComp.getTargetDb();
        p.beatCompOn = bc != null && bc.on.isSelected();
        p.beatCompTargetDb = beatComp.getTargetDb();
        p.beatNote = beatComp.getNote().name();
        p.levelerOn = lc != null && lc.on.isSelected();
        p.leveling = leveler.getLeveling();
        p.levelerSpeed = leveler.getSpeed();
        p.punchOn = uc != null && uc.on.isSelected();
        p.punchAmountDb = punch.getAmountDb();
        for (AudioProcessor d : dynamicsOrder) p.dynamicsOrder.add(procKey(d));

        p.clipOn = clipEnabled.isSelected();
        p.softClipOn = !clipCardList.isEmpty() && clipCardList.get(0).on.isSelected();
        p.softClipDb = softClip.getSatDb();
        p.softClipAlgo = softClip.getAlgorithm().name();
        p.hardClipOn = clipCardList.size() > 1 && clipCardList.get(1).on.isSelected();
        p.hardClipDb = hardClip.getClipDb();
        p.hardClipCurve = hardClip.getCurve().name();

        p.limitOn = limEnabled.isSelected();
        for (int b = 0; b < MultibandLimiterProcessor.BANDS; b++) p.mbPushDb[b] = multiband.getPushDb(b);
        p.bbPushDb = broadband.getPushDb();

        p.normalizerOn = peakEnabled.isSelected();
        p.normalizerTargetDbtp = normalizer.getTargetDbfs();
        p.osOn = osToggle != null && osToggle.isSelected();
        p.osFactor = oversampling > 1 ? oversampling
                : (osCombo != null && osCombo.getValue() != null
                   ? Integer.parseInt(osCombo.getValue().replace("x", "")) : 4);

        for (ChainModule m : chainModules) p.chainOrder.add(m.name);
        return p;
    }

    /**
     * Applies a preset to every processor and control. Listeners run (so the
     * processors get the values) but per-control re-analysis and undo capture
     * are suppressed; the caller follows up with one
     * {@link #scheduleDynamicsRefreshAfterPresetApply()}.
     */
    private void applyPreset(com.quickmaster.config.ChainPreset p)
    {
        if (p == null) return;
        applyingPreset = true;
        try
        {
            autoEqOn.setSelected(p.autoEqOn);
            if (autoEqAmountKnob != null) autoEqAmountKnob.setValue(p.autoEqAmount);
            if (autoEqAttackKnob != null) autoEqAttackKnob.setValue(p.autoEqAttackSec);
            if (autoEqReleaseKnob != null) autoEqReleaseKnob.setValue(p.autoEqReleaseSec);
            if (autoEqTarget != null)
                autoEqTarget.getSelectionModel().select(enumOr(
                        AutoEqProcessor.Target.class, p.autoEqTarget, AutoEqProcessor.Target.PINK));

            eqEnabled.setSelected(p.eqOn);
            int n = (p.bands != null) ? p.bands.size() : 0;
            updatingEqEditor = true;
            eq.setNumBands(n);
            for (int i = 0; i < n; i++)
            {
                com.quickmaster.config.ChainPreset.BandPreset bp = p.bands.get(i);
                MasterEqualizer.Band b = new MasterEqualizer.Band();
                b.type = enumOr(MasterEqualizer.BandType.class, bp.type, MasterEqualizer.BandType.BELL);
                b.channel = enumOr(MasterEqualizer.Channel.class, bp.channel, MasterEqualizer.Channel.STEREO);
                b.phase = enumOr(MasterEqualizer.BandPhase.class, bp.phase, MasterEqualizer.BandPhase.LINEAR);
                b.frequency = bp.frequency;
                b.gainDb = bp.gainDb;
                b.q = bp.q;
                b.slope = bp.slope;
                b.enabled = bp.enabled;
                b.dynamic = bp.dynamic;
                b.threshold = bp.threshold;
                b.aboveRatio = bp.aboveRatio;
                b.aboveAttackMs = bp.aboveAttackMs;
                b.aboveReleaseMs = bp.aboveReleaseMs;
                b.aboveRangeDb = bp.aboveRangeDb;
                b.aboveBoost = bp.aboveBoost;
                b.belowRatio = bp.belowRatio;
                b.belowAttackMs = bp.belowAttackMs;
                b.belowReleaseMs = bp.belowReleaseMs;
                b.belowRangeDb = bp.belowRangeDb;
                b.belowBoost = bp.belowBoost;
                eq.setBand(i, b);
            }
            eqBandCount = n;
            showBandMenu = false;
            refreshBandSelector();
            if (n > 0)
            {
                eqBandSelector.getSelectionModel().select(0);
                eqSelectedBand = 0;
                loadBandToEditor(0);
                setEqEditorDisabled(false);
            }
            else
            {
                eqSelectedBand = -1;
                setEqEditorDisabled(true);
            }
            updatingEqEditor = false;

            fade.setFadeInSec(Math.max(0.0, p.fadeInSec));
            fade.setFadeOutSec(Math.max(0.0, p.fadeOutSec));
            fade.setFadeType(enumOr(FadeProcessor.FadeType.class, p.fadeType,
                    FadeProcessor.FadeType.LINEAR));

            dynMasterEnabled.setSelected(p.dynamicsOn);
            DynCard pc = cardFor(peakComp), bc = cardFor(beatComp),
                    lc = cardFor(leveler), uc = cardFor(punch);
            if (pc != null) pc.on.setSelected(p.peakCompOn);
            if (bc != null) bc.on.setSelected(p.beatCompOn);
            if (lc != null) lc.on.setSelected(p.levelerOn);
            if (uc != null) uc.on.setSelected(p.punchOn);
            if (peakTargetKnob != null) peakTargetKnob.setValue(p.peakCompTargetDb);
            if (beatTargetKnob != null) beatTargetKnob.setValue(p.beatCompTargetDb);
            if (beatNoteCombo != null)
                beatNoteCombo.getSelectionModel().select(enumOr(
                        BeatCompProcessor.NoteValue.class, p.beatNote, BeatCompProcessor.NoteValue.QUARTER));
            if (levelingKnob != null) levelingKnob.setValue(p.leveling);
            if (levelerSpeedKnob != null) levelerSpeedKnob.setValue(p.levelerSpeed);
            if (punchKnob != null) punchKnob.setValue(p.punchAmountDb);
            if (p.dynamicsOrder != null && p.dynamicsOrder.size() == dynamicsOrder.size())
            {
                List<AudioProcessor> newDyn = new ArrayList<>();
                for (String key : p.dynamicsOrder)
                {
                    AudioProcessor d = procForKey(key);
                    if (!newDyn.contains(d)) newDyn.add(d);
                }
                if (newDyn.size() == dynamicsOrder.size())
                {
                    dynamicsOrder.clear();
                    dynamicsOrder.addAll(newDyn);
                    layoutDynamicsGrid();
                }
            }
            updateAllCompressorEnabled();

            clipEnabled.setSelected(p.clipOn);
            if (!clipCardList.isEmpty()) clipCardList.get(0).on.setSelected(p.softClipOn);
            if (clipCardList.size() > 1) clipCardList.get(1).on.setSelected(p.hardClipOn);
            if (satKnob != null) satKnob.setValue(p.softClipDb);
            if (satAlgoCombo != null)
                satAlgoCombo.getSelectionModel().select(enumOr(
                        Saturation.Algorithm.class, p.softClipAlgo, Saturation.Algorithm.TUBE));
            if (clipKnob != null) clipKnob.setValue(p.hardClipDb);
            hardClip.setCurve(enumOr(HardClipProcessor.Curve.class, p.hardClipCurve,
                    HardClipProcessor.Curve.HARD));
            updateClipEnabled();

            limEnabled.setSelected(p.limitOn);
            for (int b = 0; b < MultibandLimiterProcessor.BANDS && b < p.mbPushDb.length; b++)
            {
                if (mbPushKnobs[b] != null) mbPushKnobs[b].setValue(p.mbPushDb[b]);
                multiband.setPushDb(b, p.mbPushDb[b]);
            }
            if (bbPushKnob != null) bbPushKnob.setValue(p.bbPushDb);
            broadband.setPushDb(p.bbPushDb);

            peakEnabled.setSelected(p.normalizerOn);
            peakTarget.setValue(Math.max(peakTarget.getMin(),
                    Math.min(peakTarget.getMax(), p.normalizerTargetDbtp)));
            if (osToggle != null) osToggle.setSelected(p.osOn);
            if (osCombo != null && p.osFactor >= 2) osCombo.setValue(p.osFactor + "x");
            updateOversampling();

            // Chain order, then a manual pipeline rebuild (no extra analysis here;
            // the caller schedules one refresh for the whole apply).
            if (p.chainOrder != null && p.chainOrder.size() == chainModules.size())
            {
                List<ChainModule> newOrder = new ArrayList<>();
                for (String name : p.chainOrder)
                {
                    for (ChainModule m : chainModules)
                        if (m.name.equals(name) && !newOrder.contains(m)) newOrder.add(m);
                }
                if (newOrder.size() == chainModules.size())
                {
                    chainModules.clear();
                    chainModules.addAll(newOrder);
                    rebuildChainBar();
                    if (selectedModule != null) selectModule(selectedModule);
                }
            }
            List<AudioProcessor> order = new ArrayList<>();
            for (ChainModule m : chainModules) order.addAll(m.processors);
            order.add(normalizer);
            pipeline.setProcessors(order);
            if (loadedFile != null) player.prepare(loadedFile);

            drawEqCurve();
            drawWaveform();
        }
        finally
        {
            applyingPreset = false;
        }
    }

    /** One full refresh after a preset / undo apply (suppressed during it). */
    private void scheduleDynamicsRefreshAfterPresetApply()
    {
        if (loadedFile != null) syncLiveAnalysis();
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback)
    {
        if (name == null) return fallback;
        try { return Enum.valueOf(type, name); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    /** Saves the current chain configuration as a JSON preset file. */
    @FXML
    private void onSavePreset()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save chain preset");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("QuickMaster preset", "*.qmpreset"));
        chooser.setInitialFileName("chain.qmpreset");
        String dir = config.getOutputDir();
        File initial = new File(dir);
        if (initial.isDirectory()) chooser.setInitialDirectory(initial);
        Window window = waveformCanvas.getScene().getWindow();
        File target = chooser.showSaveDialog(window);
        if (target == null) return;
        try
        {
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(capturePreset());
            java.nio.file.Files.writeString(target.toPath(), json);
            setStatus("Preset saved: " + target.getName());
        }
        catch (Exception ex)
        {
            AppLogger.error("Could not save preset", ex);
            showError("Could not save the preset", ex.getMessage());
        }
    }

    /** Loads a JSON preset file and applies it to the whole chain. */
    @FXML
    private void onLoadPreset()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load chain preset");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("QuickMaster preset", "*.qmpreset"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        String dir = config.getOutputDir();
        File initial = new File(dir);
        if (initial.isDirectory()) chooser.setInitialDirectory(initial);
        Window window = waveformCanvas.getScene().getWindow();
        File chosen = chooser.showOpenDialog(window);
        if (chosen == null) return;
        try
        {
            String json = java.nio.file.Files.readString(chosen.toPath());
            com.quickmaster.config.ChainPreset p =
                    new com.google.gson.Gson().fromJson(json, com.quickmaster.config.ChainPreset.class);
            if (p == null) throw new IllegalArgumentException("Empty preset file.");
            undoStack.push(new EditState(null, capturePreset()));
            trimUndoHistory();
            redoStack.clear();
            updateUndoRedoButtons();
            applyPreset(p);
            scheduleDynamicsRefreshAfterPresetApply();
            setStatus("Preset loaded: " + chosen.getName());
        }
        catch (Exception ex)
        {
            AppLogger.error("Could not load preset " + chosen, ex);
            showError("Could not load the preset", ex.getMessage());
        }
    }

    /**
     * A/B settings comparison: two in-memory configuration slots. Toggling
     * stores the current chain into the active slot and applies the other one,
     * so two complete masters can be compared with one click (independent of
     * the player's A/B bypass, which compares processed vs original).
     */
    @FXML
    private void onToggleSettingsAB()
    {
        boolean toB = settingsAbButton.isSelected();
        settingsAbButton.setText(toB ? "Set B" : "Set A");
        if (toB)
        {
            settingsSlotA = capturePreset();
            if (settingsSlotB == null) settingsSlotB = capturePreset();   // first switch: B starts as a copy
            applyPreset(settingsSlotB);
        }
        else
        {
            settingsSlotB = capturePreset();
            if (settingsSlotA == null) settingsSlotA = capturePreset();
            applyPreset(settingsSlotA);
        }
        scheduleDynamicsRefreshAfterPresetApply();
        setStatus("Settings " + (toB ? "B" : "A") + " active.");
    }

    /* =========================================================
     *  Loop region (selection looping)
     * ========================================================= */

    /** Toggles looping over the waveform selection (or the whole file). */
    @FXML
    private void onToggleLoop()
    {
        if (loadedFile == null)
        {
            if (loopButton != null) loopButton.setSelected(false);
            return;
        }
        if (loopButton.isSelected())
        {
            applyLoopFromSelection();
            setStatus(hasSelection() ? "Looping the selection." : "Looping the whole track.");
        }
        else
        {
            player.clearLoopRegion();
            setStatus("Loop off.");
        }
    }

    /** Sets the player's loop region from the selection (whole file if none). */
    private void applyLoopFromSelection()
    {
        if (loadedFile == null) return;
        long total = loadedFile.getSamples().length / loadedFile.getChannels();
        long s = 0, e = total;
        if (hasSelection())
        {
            double a = Math.min(selStartSec, selEndSec), b = Math.max(selStartSec, selEndSec);
            s = Math.round(a * loadedFile.getSampleRate());
            e = Math.round(b * loadedFile.getSampleRate());
        }
        player.setLoopRegion(s, e);
    }

    /* =========================================================
     *  Batch export
     * ========================================================= */

    /** Batch settings: destination + target format + encoding (sampleRate 0 = keep source). */
    private record BatchSettings(File outDir, boolean mp3, int sampleRate,
                                 int bitDepth, boolean isFloat, int kbps) { }

    /**
     * Masters a whole folder with the current chain: the user picks a source
     * folder, every {@code .wav} / {@code .mp3} in it (alphabetical, not
     * recursive) is loaded, analysed on its own snapshot (its own tempo/onset
     * analysis), rendered with the same settings, and saved as
     * {@code <name>-mastered.<ext>} into a chosen destination folder. Runs on
     * one background task with the export overlay (cancellable between blocks).
     */
    @FXML
    private void onBatchExport()
    {
        String dir = config.getOutputDir();
        File initial = new File(dir);
        Window window = waveformCanvas.getScene().getWindow();

        javafx.stage.DirectoryChooser srcChooser = new javafx.stage.DirectoryChooser();
        srcChooser.setTitle("Batch export: choose the folder with the songs");
        if (initial.isDirectory()) srcChooser.setInitialDirectory(initial);
        File srcDir = srcChooser.showDialog(window);
        if (srcDir == null) return;

        File[] found = srcDir.listFiles(f ->
        {
            if (!f.isFile()) return false;
            String n = f.getName().toLowerCase(Locale.ROOT);
            return n.endsWith(".wav") || n.endsWith(".mp3");
        });
        if (found == null || found.length == 0)
        {
            showError("No audio files found",
                    "The folder \"" + srcDir.getName() + "\" contains no .wav or .mp3 files.");
            return;
        }
        Arrays.sort(found, java.util.Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        List<File> files = Arrays.asList(found);

        BatchSettings settings = askBatchSettings(srcDir, files.size());
        if (settings == null) { setStatus("Batch export cancelled."); return; }
        File outDir = settings.outDir();
        try
        {
            java.nio.file.Files.createDirectories(outDir.toPath());
        }
        catch (Exception ex)
        {
            showError("Cannot create the destination folder", ex.getMessage());
            return;
        }

        player.stop();
        final List<File> sources = new ArrayList<>(files);
        final int os = oversampling;

        Task<Void> task = new Task<>()
        {
            @Override
            protected Void call() throws Exception
            {
                int n = sources.size();
                for (int i = 0; i < n; i++)
                {
                    if (isCancelled()) return null;
                    File srcFile = sources.get(i);
                    final int index = i;
                    Platform.runLater(() -> exportFileLabel.setText(
                            (index + 1) + " / " + n + ": " + srcFile.getName()));

                    AudioFile audio = AudioFormatDetector.loadAuto(srcFile.getAbsolutePath());
                    audio.load();

                    TrackAnalysis ta = new TrackAnalysis();
                    ta.analyze(audio.getSamples(), audio.getChannels(), audio.getSampleRate());

                    Snapshot snap = buildSnapshot(ta);
                    final double base = (double) index / n;
                    float[] processed = renderOversampled(snap.pipeline,
                            audio.getSamples().clone(), audio.getSampleRate(),
                            audio.getChannels(), os,
                            frac ->
                            {
                                if (isCancelled())
                                    throw new java.util.concurrent.CancellationException();
                                updateProgress(base + frac / n, 1.0);
                            });
                    if (isCancelled()) return null;

                    int outRate = (settings.sampleRate() > 0)
                            ? settings.sampleRate() : audio.getSampleRate();
                    float[] out = resampleForExport(processed, audio.getChannels(),
                            audio.getSampleRate(), outRate);
                    if (outRate != audio.getSampleRate() && snap.normalizer.isEnabled())
                    {
                        reclampTruePeak(out, audio.getChannels(), snap.normalizer.getTargetDbfs());
                    }

                    String baseName = srcFile.getName();
                    int dot = baseName.lastIndexOf('.');
                    if (dot > 0) baseName = baseName.substring(0, dot);
                    String ext = settings.mp3() ? ".mp3" : ".wav";
                    String outPath = new File(outDir, baseName + "-mastered" + ext).getAbsolutePath();

                    if (settings.mp3())
                    {
                        new Mp3File(outPath, outRate, audio.getChannels(), out,
                                settings.kbps(), false).save(outPath);
                    }
                    else
                    {
                        new WavFile(outPath, outRate, audio.getChannels(), out,
                                settings.bitDepth(), settings.isFloat()).save(outPath);
                    }
                    MetadataPreserver.preserve(srcFile.getAbsolutePath(), outPath);
                }
                return null;
            }
        };
        task.setOnSucceeded(ev ->
        {
            hideExportOverlay();
            setStatus("Batch export done: " + sources.size() + " files → " + outDir.getName());
            AppLogger.info("Batch export finished into " + outDir.getAbsolutePath());
            config.setOutputDir(outDir.getAbsolutePath());
        });
        task.setOnFailed(ev ->
        {
            hideExportOverlay();
            AppLogger.error("Batch export failed", task.getException());
            setStatus("Batch export failed.");
            showError("Batch export failed", task.getException().getMessage());
        });
        task.setOnCancelled(ev ->
        {
            hideExportOverlay();
            setStatus("Batch export cancelled.");
        });
        exportTask = task;
        showExportOverlay(sources.size() + " files", task);
        runTask(task);
    }

    /**
     * The one batch dialog: states what will be applied (the chain exactly as
     * configured right now), shows and lets the user change the destination
     * folder (default: a "mastered" subfolder of the source), and asks for the
     * encoding (format, sample rate, depth / bitrate).
     */
    private BatchSettings askBatchSettings(File srcDir, int fileCount)
    {
        ComboBox<String> fmtCombo = new ComboBox<>();
        fmtCombo.getItems().addAll("WAV", "MP3");
        fmtCombo.setValue("WAV");

        ComboBox<String> srCombo = new ComboBox<>();
        srCombo.getItems().addAll("Keep source", "44100", "48000", "88200", "96000", "176400", "192000");
        srCombo.setValue("Keep source");

        ComboBox<String> bitCombo = new ComboBox<>();
        bitCombo.getItems().addAll("16-bit", "24-bit", "32-bit float");
        bitCombo.setValue("24-bit");

        ComboBox<Integer> kbpsCombo = new ComboBox<>();
        kbpsCombo.getItems().addAll(96, 128, 160, 192, 224, 256, 320);
        kbpsCombo.setValue(320);

        // Destination: visible, editable, defaulting to <source>/mastered.
        final File[] outDirHolder = { new File(srcDir, "mastered") };
        Label outDirLabel = new Label(outDirHolder[0].getAbsolutePath());
        outDirLabel.getStyleClass().add("value");
        outDirLabel.setMaxWidth(320);
        Button chooseOut = new Button("Change…");
        chooseOut.setOnAction(ev ->
        {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("Batch export: destination folder");
            if (srcDir.isDirectory()) dc.setInitialDirectory(srcDir);
            File chosen = dc.showDialog(chooseOut.getScene().getWindow());
            if (chosen != null)
            {
                outDirHolder[0] = chosen;
                outDirLabel.setText(chosen.getAbsolutePath());
            }
        });
        HBox outRow = new HBox(8, outDirLabel, chooseOut);
        outRow.setAlignment(Pos.CENTER_LEFT);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.addRow(0, new Label("Source"), new Label(
                srcDir.getAbsolutePath() + "  (" + fileCount
                        + (fileCount == 1 ? " song)" : " songs)")));
        g.addRow(1, new Label("Destination"), outRow);
        g.addRow(2, new Label("Format"), fmtCombo);
        g.addRow(3, new Label("Sample rate (Hz)"), srCombo);
        Label encLabel = new Label("Bit depth");
        g.addRow(4, encLabel, bitCombo);
        Label note = new Label(
                "Applies the chain exactly as it is configured right now.\n"
                        + "Each song is saved as <name>-mastered." + "wav" + " into the destination.");
        note.getStyleClass().add("value-muted");
        note.setWrapText(true);
        g.add(note, 0, 5, 2, 1);
        fmtCombo.valueProperty().addListener((o, ov, nv) ->
        {
            boolean mp3 = "MP3".equals(nv);
            encLabel.setText(mp3 ? "Bitrate (kbps)" : "Bit depth");
            g.getChildren().removeAll(bitCombo, kbpsCombo);
            g.add(mp3 ? kbpsCombo : bitCombo, 1, 4);
            note.setText("Applies the chain exactly as it is configured right now.\n"
                    + "Each song is saved as <name>-mastered." + (mp3 ? "mp3" : "wav")
                    + " into the destination.");
        });

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Batch export");
        dlg.setHeaderText("Master " + fileCount + (fileCount == 1 ? " song" : " songs")
                + " with the current chain");
        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return null;

        File outDir = outDirHolder[0];
        boolean mp3 = "MP3".equals(fmtCombo.getValue());
        int sr = "Keep source".equals(srCombo.getValue()) ? 0 : Integer.parseInt(srCombo.getValue());
        if (mp3) return new BatchSettings(outDir, true, sr, 16, false, kbpsCombo.getValue());
        String bd = bitCombo.getValue();
        if (bd.startsWith("16")) return new BatchSettings(outDir, false, sr, 16, false, 0);
        if (bd.startsWith("24")) return new BatchSettings(outDir, false, sr, 24, false, 0);
        return new BatchSettings(outDir, false, sr, 32, true, 0);
    }

    /* =========================================================
     *  Limit panel (multiband + broadband true-peak limiting)
     * ========================================================= */

    private static final double MB_METER_W = 72.0;      // band GR-meter width
    private static final double BB_METER_W = 104.0;     // broadband GR-meter width
    private static final String[] BAND_COLORS = { "#5a8dee", "#5ad1c4", "#e0b34a", "#e87ad1" };

    private final java.util.List<Region> mbFills = new java.util.ArrayList<>();
    private final java.util.List<Label> mbGrLabels = new java.util.ArrayList<>();
    private Region bbFill;
    private Label bbGrLabel;
    private long prevLimPos = -1L;                                          // last metered playhead
    private final double[] mbHeld = new double[MultibandLimiterProcessor.BANDS];  // held GR per band
    private double bbHeld = 0.0;                                            // held broadband GR

    // Push rebuilds (O(N) envelope) run off the FX thread, coalesced, so the knobs stay fluid.
    private final java.util.concurrent.ExecutorService limiterExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r ->
            {
                Thread t = new Thread(r, "limiter-rebuild");
                t.setDaemon(true);
                return t;
            });
    private final double[] mbTarget = new double[MultibandLimiterProcessor.BANDS];
    private final java.util.concurrent.atomic.AtomicBoolean[] mbPending =
            new java.util.concurrent.atomic.AtomicBoolean[MultibandLimiterProcessor.BANDS];
    private double bbTarget = 0.0;
    private final java.util.concurrent.atomic.AtomicBoolean bbPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void initLimiterUi()
    {
        limEnabled.selectedProperty().addListener((o, ov, nv) ->
        {
            multiband.setEnabled(nv);
            broadband.setEnabled(nv);
        });
        multiband.setEnabled(limEnabled.isSelected());
        broadband.setEnabled(limEnabled.isSelected());
        for (int b = 0; b < MultibandLimiterProcessor.BANDS; b++)
            mbPending[b] = new java.util.concurrent.atomic.AtomicBoolean();

        limContent.getChildren().clear();
        limContent.setSpacing(6);

        // One flat row of cards (no boxes): the four multiband bands, a divider, then
        // the larger broadband Push. Cards fill the full panel height like the Clip tab.
        HBox row = new HBox(0);
        row.setAlignment(Pos.CENTER);
        row.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(row, Priority.ALWAYS);

        for (int b = 0; b < MultibandLimiterProcessor.BANDS; b++)
        {
            final int band = b;
            Knob k = new Knob(MultibandLimiterProcessor.BAND_NAMES[b], 0.0,
                    MultibandLimiterProcessor.MAX_PUSH_DB, multiband.getPushDb(b))
                    .accent(BAND_COLORS[b]).scale(1.5)
                    .formatter(v -> String.format(Locale.US, "%.1f dB", v))
                    .tooltip("Push the " + MultibandLimiterProcessor.BAND_NAMES[band]
                            + " band until its loudest peak is limited by this many dB.");
            k.valueProperty().addListener((o, ov, nv) -> scheduleMbPush(band, nv.doubleValue()));
            k.disableProperty().bind(limEnabled.selectedProperty().not());
            mbPushKnobs[b] = k;

            Object[] m = limMeter(MB_METER_W);
            mbFills.add((Region) m[1]);
            mbGrLabels.add((Label) m[2]);
            VBox card = limCard(k, (StackPane) m[0], (Label) m[2]);
            HBox.setHgrow(card, Priority.ALWAYS);
            row.getChildren().add(card);
        }

        Region div = new Region();
        div.setMinWidth(1);
        div.setMaxWidth(1);
        div.setStyle("-fx-background-color: #3a3a44;");
        row.getChildren().add(div);

        Knob bk = new Knob("PUSH", 0.0, BroadbandLimiterProcessor.MAX_PUSH_DB, broadband.getPushDb())
                .accent("#f0b14a").scale(2.1)
                .formatter(v -> String.format(Locale.US, "%.1f dB", v))
                .tooltip("Push the whole mix until its loudest true peak is limited by this many dB.");
        bk.valueProperty().addListener((o, ov, nv) -> scheduleBbPush(nv.doubleValue()));
        bk.disableProperty().bind(limEnabled.selectedProperty().not());
        bbPushKnob = bk;

        Object[] bm = limMeter(BB_METER_W);
        bbFill = (Region) bm[1];
        bbGrLabel = (Label) bm[2];
        VBox bbCard = limCard(bk, (StackPane) bm[0], bbGrLabel);
        HBox.setHgrow(bbCard, Priority.ALWAYS);
        row.getChildren().add(bbCard);

        limContent.getChildren().add(row);

        // Clip the panel so a wide row can never overflow onto (and intercept the mouse
        // events of) the right sidebar, e.g. the Peak Normalizer controls.
        javafx.scene.shape.Rectangle limClip = new javafx.scene.shape.Rectangle();
        limClip.widthProperty().bind(limiterPanel.widthProperty());
        limClip.heightProperty().bind(limiterPanel.heightProperty());
        limiterPanel.setClip(limClip);
    }

    /** A flat limiter card (no background box): a knob filling the height + a GR meter below. */
    private VBox limCard(Knob knob, StackPane meter, Label grLabel)
    {
        StackPane controlBox = new StackPane(knob);
        controlBox.setAlignment(Pos.CENTER);
        VBox.setVgrow(controlBox, Priority.ALWAYS);
        HBox meterRow = new HBox(8, meter, grLabel);
        meterRow.setAlignment(Pos.CENTER);
        VBox card = new VBox(12, controlBox, meterRow);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new javafx.geometry.Insets(6, 6, 8, 6));
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        return card;
    }

    /**
     * A horizontal GR meter: a fixed-size track with an overlaid fill whose width
     * never exceeds the track, so it cannot resize the panel. Returns
     * {@code [meter (StackPane), fill (Region), grLabel (Label)]}.
     */
    private Object[] limMeter(double width)
    {
        Region track = new Region();
        track.getStyleClass().add("gr-meter-track");
        track.setMinSize(width, 8);
        track.setPrefSize(width, 8);
        track.setMaxSize(width, 8);
        Region fill = new Region();
        fill.getStyleClass().add("gr-meter-fill");
        fill.setMaxHeight(8);
        fill.setPrefWidth(0);
        fill.setMaxWidth(0);
        StackPane meter = new StackPane(track, fill);
        meter.setMaxWidth(width);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        Label grLabel = new Label("0.0 dB");
        grLabel.getStyleClass().add("value");
        grLabel.setMinWidth(42);
        return new Object[]{ meter, fill, grLabel };
    }

    /** Sets a band's push without blocking the FX thread: the envelope rebuild is
     *  coalesced onto a background thread (the gain envelope publishes atomically). */
    private void scheduleMbPush(int band, double v)
    {
        mbTarget[band] = v;
        if (mbPending[band].compareAndSet(false, true))
        {
            limiterExec.submit(() ->
            {
                mbPending[band].set(false);
                multiband.setPushDb(band, mbTarget[band]);
            });
        }
    }

    private void scheduleBbPush(double v)
    {
        bbTarget = v;
        if (bbPending.compareAndSet(false, true))
        {
            limiterExec.submit(() ->
            {
                bbPending.set(false);
                broadband.setPushDb(bbTarget);
            });
        }
    }

    /**
     * Updates the Limit meters: each fill's width is set (never exceeding its fixed
     * track) so the bars cannot resize the panel. Reads
     * the deepest reduction since the last tick and holds it with a slow fall, and
     * scales each bar to its band's dialled push so it stays readable at any push.
     */
    private void updateLimiterMeters()
    {
        long pos = (player != null) ? player.getPositionSamples() : 0L;
        long from = (prevLimPos >= 0 && prevLimPos < pos) ? prevLimPos : pos;
        prevLimPos = pos;
        final double decay = 0.85;   // hold, then fall to rest over ~0.5 s at 60 fps

        for (int b = 0; b < mbFills.size(); b++)
        {
            double gr = Math.abs(multiband.getBandGrDeepest(b, from, pos));
            mbHeld[b] = Math.max(gr, mbHeld[b] * decay);
            double push = Math.max(multiband.getPushDb(b), 0.1);   // bar relative to the dialled push
            double frac = Math.min(mbHeld[b] / push, 1.0);
            Region fill = mbFills.get(b);
            fill.setPrefWidth(frac * MB_METER_W);
            fill.setMaxWidth(frac * MB_METER_W);
            mbGrLabels.get(b).setText(String.format(Locale.US, "%.1f dB", -mbHeld[b]));
        }
        if (bbFill != null)
        {
            double gr = Math.abs(broadband.getGrDeepest(from, pos));
            bbHeld = Math.max(gr, bbHeld * decay);
            double push = Math.max(broadband.getPushDb(), 0.1);
            double frac = Math.min(bbHeld / push, 1.0);
            bbFill.setPrefWidth(frac * BB_METER_W);
            bbFill.setMaxWidth(frac * BB_METER_W);
            bbGrLabel.setText(String.format(Locale.US, "%.1f dB", -bbHeld));
        }
    }

    /* =========================================================
     *  Waveform rendering
     * ========================================================= */

    /**
     * Downsamples the current audio for display. A 3-minute song
     * at 48 kHz contains roughly 17 million samples; drawing one
     * per pixel is impossible on a typical screen. Instead, for
     * each pixel column, this method records the maximum
     * absolute sample value among the frames that map to that
     * column. The result is a small {@code float[]} of size
     * equal to the canvas width that captures the audio's
     * amplitude envelope faithfully for visual purposes.
     */
    private void downsampleForDisplay()
    {
        if (loadedFile == null) return;
        float[] src = loadedFile.getSamples();
        int channels = loadedFile.getChannels();
        int width = (int) waveformCanvas.getWidth();
        if (width <= 0 || src.length == 0)
        {
            waveformDownsampled = new float[0];
            return;
        }

        long totalFrames = src.length / channels;
        long framesPerPixel = Math.max(1, totalFrames / width);

        float[] out = new float[width];
        for (int x = 0; x < width; x++)
        {
            long startFrame = (long) x * framesPerPixel;
            long endFrame = Math.min(totalFrames, startFrame + framesPerPixel);
            float max = 0.0f;
            for (long f = startFrame; f < endFrame; f++)
            {
                for (int c = 0; c < channels; c++)
                {
                    float v = src[(int) (f * channels + c)];
                    float abs = v >= 0 ? v : -v;
                    if (abs > max) max = abs;
                }
            }
            out[x] = max;
        }
        waveformDownsampled = out;
    }

    /**
     * Full redraw of the waveform area: solid background, centre
     * line, amplitude bars per pixel column, and the playback
     * cursor. Called whenever the audio, the canvas size, or the
     * play position changes.
     */
    private void drawWaveform()
    {
        GraphicsContext gc = waveformCanvas.getGraphicsContext2D();
        double w = waveformCanvas.getWidth();
        double h = waveformCanvas.getHeight();

        gc.setFill(Color.web("#14141a"));
        gc.fillRect(0, 0, w, h);

        gc.setStroke(Color.web("#2d2d35"));
        gc.setLineWidth(1.0);
        gc.strokeLine(0, h / 2.0, w, h / 2.0);

        if (waveformDownsampled == null || waveformDownsampled.length == 0)
        {
            return;
        }

        gc.setStroke(Color.web("#4a9eff"));
        gc.setLineWidth(1.0);
        double mid = h / 2.0;
        for (int x = 0; x < waveformDownsampled.length; x++)
        {
            float amp = waveformDownsampled[x];
            double yTop    = mid - amp * mid * 0.92;
            double yBottom = mid + amp * mid * 0.92;
            gc.strokeLine(x, yTop, x, yBottom);
        }

        // Fade envelope: ramps + prominent draggable handles (drag on the wave).
        if (loadedFile != null)
        {
            double dur = loadedFile.getDuration();
            if (dur > 0.0)
            {
                double inX  = (fade.getFadeInSec() / dur) * w;
                double outX = ((dur - fade.getFadeOutSec()) / dur) * w;
                FadeProcessor.FadeType ft = fade.getFadeType();
                gc.setStroke(Color.web("#f0b14a"));
                gc.setLineWidth(2.0);
                if (fade.getFadeInSec() > 0.0 && inX >= 1.0)
                {
                    gc.beginPath();
                    for (int px = 0; px <= inX; px++)
                    {
                        double y = h - fadeShape(px / inX, ft) * h;
                        if (px == 0) gc.moveTo(px, y); else gc.lineTo(px, y);
                    }
                    gc.stroke();
                }
                if (fade.getFadeOutSec() > 0.0 && outX <= w - 1.0)
                {
                    gc.beginPath();
                    for (int px = (int) outX; px <= w; px++)
                    {
                        double y = h - fadeShape((w - px) / (w - outX), ft) * h;
                        if (px == (int) outX) gc.moveTo(px, y); else gc.lineTo(px, y);
                    }
                    gc.stroke();
                }
                drawFadeHandle(gc, inX);
                drawFadeHandle(gc, outX);
                gc.setFill(Color.web("#f0b14a"));
                gc.setFont(Font.font(10));
                if (fade.getFadeInSec()  > 0.0)
                    gc.fillText(formatSec(fade.getFadeInSec()),  Math.min(inX + 8, w - 46), 16);
                if (fade.getFadeOutSec() > 0.0)
                    gc.fillText(formatSec(fade.getFadeOutSec()), Math.max(outX - 46, 4), 16);
                gc.setFill(Color.web("#8a8a93"));
                gc.setFont(Font.font(9));
                gc.fillText("fade: " + ft.name().toLowerCase(Locale.US) + " (wheel over a handle to change)",
                        4, h - 4);
            }
        }

        // Range selection overlay (shift-drag) for Crop / Delete.
        if (hasSelection())
        {
            double dur = loadedFile.getDuration();
            double x1 = (Math.min(selStartSec, selEndSec) / dur) * w;
            double x2 = (Math.max(selStartSec, selEndSec) / dur) * w;
            gc.setFill(Color.web("#4a9eff", 0.22));
            gc.fillRect(x1, 0, x2 - x1, h);
            gc.setStroke(Color.web("#4a9eff"));
            gc.setLineWidth(1.0);
            gc.strokeLine(x1, 0, x1, h);
            gc.strokeLine(x2, 0, x2, h);
        }

        if (loadedFile != null)
        {
            long pos = player.getPositionSamples();
            long total = loadedFile.getSamples().length / loadedFile.getChannels();
            if (total > 0)
            {
                double x = (pos / (double) total) * w;
                gc.setStroke(Color.web("#f0b14a"));
                gc.setLineWidth(1.5);
                gc.strokeLine(x, 0, x, h);
            }
        }
    }

    /** Draws a prominent diamond fade handle at the top of the waveform. */
    private void drawFadeHandle(GraphicsContext gc, double x)
    {
        double cy = 9, r = 7;
        double[] xs = { x, x + r, x, x - r };
        double[] ys = { cy - r, cy, cy + r, cy };
        gc.setFill(Color.web("#f0b14a"));
        gc.fillPolygon(xs, ys, 4);
        gc.setStroke(Color.web("#16161c"));
        gc.setLineWidth(1.2);
        gc.strokePolygon(xs, ys, 4);
    }

    /** Maps a linear level fraction through the current fade curve (for drawing). */
    private static double fadeShape(double t, FadeProcessor.FadeType type)
    {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        switch (type)
        {
            case EXPONENTIAL: return t * t;
            case LOGARITHMIC: return Math.sqrt(t);
            case SCURVE:      return t * t * (3.0 - 2.0 * t);
            default:          return t;
        }
    }

    /** Measures the raw input file's peak (no processing) and shows it. */
    private void measureInputPeak()
    {
        if (loadedFile == null) { peakInputLabel.setText("·"); return; }
        float[] s = loadedFile.getSamples();
        float peak = 0.0f;
        for (float v : s) { float a = (v >= 0.0f) ? v : -v; if (a > peak) peak = a; }
        double db = (peak <= 0.0f) ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(peak);
        peakInputLabel.setText(Double.isInfinite(db) ? "-∞ dB"
                : String.format(Locale.US, "%.1f dBFS", db));
    }

    /* =========================================================
     *  Helpers
     * ========================================================= */

    /**
     * Updates the position label with the current play time and
     * total duration formatted as {@code M:SS.mmm}.
     */
    private void updatePositionLabel()
    {
        if (loadedFile == null)
        {
            positionLabel.setText("0:00.000 / 0:00.000");
            return;
        }
        long pos = player.getPositionSamples();
        double sec = pos / (double) loadedFile.getSampleRate();
        double total = loadedFile.getDuration();
        positionLabel.setText(formatTime(sec) + " / " + formatTime(total));
    }

    /** Formats a duration in seconds as {@code M:SS.mmm}. */
    private static String formatTime(double sec)
    {
        int minutes = (int) (sec / 60.0);
        double remaining = sec - minutes * 60.0;
        return String.format(Locale.US, "%d:%06.3f", minutes, remaining);
    }

    /** Formats a dB gain with explicit sign, e.g. {@code +3.5 dB}. */
    private static String formatDb(double v)
    {
        return String.format(Locale.US, "%+.1f dB", v);
    }

    /** Formats a dBFS target, e.g. {@code -6.0 dBFS}. */
    private static String formatDbfs(double v)
    {
        return String.format(Locale.US, "%.1f dBFS", v);
    }

    /** Formats a duration in seconds with two decimals. */
    private static String formatSec(double v)
    {
        return String.format(Locale.US, "%.2f s", v);
    }

    /** Sets the status bar text. */
    private void setStatus(String msg)
    {
        statusLabel.setText(msg);
    }

    /**
     * Shows a modal error dialog with the given header and
     * details. Used for failures that the user must be informed
     * about (load, save, etc.).
     */
    private void showError(String header, String details)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("QuickMaster");
        alert.setHeaderText(header);
        alert.setContentText(details != null ? details : "Unknown error.");
        alert.showAndWait();
    }

    /**
     * Runs a Task on a background daemon thread. The Task's
     * {@code onSucceeded} and {@code onFailed} callbacks are
     * fired automatically on the FX thread by the framework,
     * so UI updates inside them are safe.
     *
     * @param task  the task to run
     */
    private void runTask(Task<?> task)
    {
        Thread t = new Thread(task, "QuickMaster-Task");
        t.setDaemon(true);
        t.start();
    }
}