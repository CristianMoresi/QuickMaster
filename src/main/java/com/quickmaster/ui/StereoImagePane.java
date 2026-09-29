package com.quickmaster.ui;

import com.quickmaster.processing.stereo.*;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.Locale;
import java.util.function.Consumer;

/** Presentation only. Whole-file analysis and synthesis belong to workers. */
public final class StereoImagePane extends VBox {
    // Model bridge for the common chain chips; not a second visible power control.
    private final CheckBox enabled = new CheckBox();
    private final ToggleButton power = new ToggleButton("Bypassed");
    private final ToggleButton generation = sectionSwitch("stereoGeneration");
    private final ToggleButton leveling = sectionSwitch("stereoLeveler");
    private final ToggleButton guard = sectionSwitch("stereoGuard");
    private final ComboBox<StereoProfile> profile = new ComboBox<>();
    private final Knob generationAmount = percent(StereoImageSettings.DEFAULT.generationAmount()*100), levelingAmount = percent(35);
    private final Knob margin = new Knob("Margin", 0, 12, 3).scale(1.5).accent("#64c8bc")
            .formatter(v -> String.format(Locale.US, "%.1f dB", v));
    private final Knob reference = new Knob("Target Side", .1, 49, 12).scale(.7)
            .formatter(v -> String.format(Locale.US, "%.1f %%", v));
    private final Slider lowCut = new Slider(0, 1, 0), sideGain = new Slider(-12, 12, 0);
    private final Label lowValue = new Label("Off"), sideValue = new Label("0.0 dB");
    private final Label target = new Label("Select a section to process stereo"), balance = new Label();
    private final Label notice = new Label(), status = new Label(), levelGain = new Label(), guardGain = new Label();
    private final ProgressBar energyBar = new ProgressBar(0);
    private Consumer<StereoImageSettings> onChange = s -> {};
    private boolean applying;
    private String outputWarning = "";

    public StereoImagePane(Runnable loadReference) {
        setId("stereoImagePane"); getStyleClass().addAll("eq-panel", "stereo-pane");
        setSpacing(10); setPadding(new Insets(12)); setMinSize(0, 0);
        enabled.setId("stereoEnabled"); enabled.setVisible(false); enabled.setManaged(false);
        power.setId("stereoPower"); power.getStyleClass().add("stereo-power");
        power.setAccessibleText("Enable Stereo Image");
        power.selectedProperty().bindBidirectional(enabled.selectedProperty());
        power.selectedProperty().addListener((o,a,b) -> power.setText(b ? "Enabled" : "Bypassed"));
        Label title = new Label("STEREO IMAGE"); title.getStyleClass().add("stereo-title");
        status.getStyleClass().add("stereo-status");
        HBox header = new HBox(12, title, spacer(), status, power); header.setAlignment(Pos.CENTER_LEFT);
        profile.setId("stereoProfile"); profile.getItems().setAll(StereoProfile.values());
        profile.setPrefWidth(230); profile.setMinWidth(140);
        profile.setTooltip(new Tooltip("Energy references for Side Leveler / Side Guard, not generation strength. Genre profiles are measured references, not universal limits. Auto follows this track."));
        Button referenceButton = new Button("Reference…"); referenceButton.setId("stereoLoadReference");
        referenceButton.setOnAction(e -> loadReference.run()); reference.setId("stereoReferenceShare");
        HBox selector = new HBox(10, new Label("Balance profile"), profile, referenceButton, reference);
        selector.setAlignment(Pos.CENTER_LEFT);
        target.setId("stereoTargetReadout"); target.getStyleClass().add("stereo-target");
        balance.setId("stereoBalanceReadout");
        HBox numbers = new HBox(12, target, spacer(), balance); numbers.setAlignment(Pos.CENTER_LEFT);
        energyBar.setMaxWidth(Double.MAX_VALUE); energyBar.setMinHeight(6); energyBar.setPrefHeight(6);
        VBox meter = new VBox(8, numbers, energyBar); meter.getStyleClass().add("stereo-meter");
        meter.setPadding(new Insets(10, 12, 10, 12));
        generationAmount.setId("stereoGenerationAmount"); levelingAmount.setId("stereoLevelingAmount"); margin.setId("stereoGuardMargin");
        HBox cards = new HBox(10,
                card("Generation", generation, generationAmount, "Add new stereo", new Label()),
                card("Side Leveler", leveling, levelingAmount, "Balance stereo width", levelGain),
                card("Side Guard", guard, margin, "Control excessive width", guardGain));
        for (var child : cards.getChildren()) { HBox.setHgrow(child, Priority.ALWAYS); ((Region)child).setPrefWidth(250); }
        VBox.setVgrow(cards, Priority.ALWAYS);
        lowCut.setId("stereoLowCut"); sideGain.setId("stereoSideGain");
        lowCut.setAccessibleText("Generated Stereo Low Cut"); sideGain.setAccessibleText("Side Gain");
        lowValue.setId("stereoLowCutValue"); sideValue.setId("stereoSideGainValue");
        lowCut.setBlockIncrement(.025); sideGain.setBlockIncrement(.5);
        lowCut.setTooltip(new Tooltip("Linear-phase low cut on generated stereo only. Leftmost: Off, all frequencies generated. 20 Hz–5 kHz; the original Mid and Side are untouched. At the source Nyquist limit all generated content is excluded."));
        sideGain.setTooltip(new Tooltip("Final gain of combined Side, after Side Leveler and Side Guard. Mid is unchanged. Positive gain can exceed the Guard target; allow output headroom."));
        lowCut.setOnMouseClicked(e -> { if(e.getClickCount() == 2) lowCut.setValue(0); });
        sideGain.setOnMouseClicked(e -> { if(e.getClickCount() == 2) sideGain.setValue(0); });
        HBox trims = new HBox(10, sliderCard("Generated Stereo Low Cut", lowCut, lowValue, "Off", "5 kHz"),
                sliderCard("Side Gain", sideGain, sideValue, "−12 dB", "+12 dB"));
        for(var child : trims.getChildren()) { HBox.setHgrow(child, Priority.ALWAYS); ((Region)child).setPrefWidth(380); }
        generationAmount.tooltip("Mix the generated stereo track into the original. The approved 0–100 range is unchanged. No automatic output attenuation; use Limit / Peak Normalizer explicitly if needed.");
        levelingAmount.tooltip("Scale the smooth offline Side correction toward the selected balance. 0%: no regulation; 100%: full correction within the +12 / -24 dB gain limits.");
        margin.tooltip("Permitted Side/Mid ratio above the target, in dB. Side Guard only attenuates; it is not a true-peak limiter. Final Side Gain is independent.");
        notice.setId("stereoNotice"); notice.setWrapText(true); notice.getStyleClass().add("stereo-notice");
        notice.managedProperty().bind(notice.textProperty().isNotEmpty());
        getChildren().addAll(header, selector, meter, cards, trims, notice, enabled);
        for(ToggleButton toggle : new ToggleButton[]{generation, leveling, guard}) toggle.selectedProperty().addListener((o,a,b) -> changed());
        for(Knob knob : new Knob[]{generationAmount, levelingAmount, margin, reference}) knob.valueProperty().addListener((o,a,b) -> changed());
        lowCut.valueProperty().addListener((o,a,b) -> changed()); sideGain.valueProperty().addListener((o,a,b) -> changed());
        profile.valueProperty().addListener((o,a,b) -> changed()); apply(StereoImageSettings.DEFAULT);
    }
    private static Region spacer() { Region r = new Region(); HBox.setHgrow(r, Priority.ALWAYS); return r; }
    private static ToggleButton sectionSwitch(String id) {
        ToggleButton t = new ToggleButton("Off"); t.setId(id); t.getStyleClass().add("stereo-switch");
        t.selectedProperty().addListener((o,a,b) -> t.setText(b ? "On" : "Off")); return t;
    }
    private static Knob percent(double initial) {
        return new Knob("Amount", 0, 100, initial).scale(1.5).accent("#64c8bc")
                .formatter(v -> String.format(Locale.US, "%.0f %%", v));
    }
    private static VBox card(String name, ToggleButton toggle, Knob knob, String hint, Label gain) {
        toggle.setAccessibleText("Enable " + name);
        Label title = new Label(name); title.getStyleClass().add("stereo-card-title");
        HBox heading = new HBox(8, title, spacer(), toggle); heading.setAlignment(Pos.CENTER_LEFT);
        Label description = new Label(hint); description.getStyleClass().add("stereo-description");
        gain.setMinHeight(17); gain.getStyleClass().add("stereo-gain-readout");
        VBox body = new VBox(6, knob, description, gain); body.setAlignment(Pos.CENTER); VBox.setVgrow(body, Priority.ALWAYS);
        VBox card = new VBox(8, heading, body); card.setPadding(new Insets(12)); card.setMinWidth(0);
        card.getStyleClass().add("stereo-card");
        toggle.selectedProperty().addListener((o,a,b) -> card.pseudoClassStateChanged(PseudoClass.getPseudoClass("on"), b)); return card;
    }
    private static VBox sliderCard(String name, Slider slider, Label value, String left, String right) {
        value.getStyleClass().add("stereo-value"); value.setMinWidth(78);
        value.setAlignment(Pos.CENTER_RIGHT); value.setMouseTransparent(true);
        HBox heading = new HBox(8, new Label(name), spacer(), value); heading.setAlignment(Pos.CENTER_LEFT);
        Label lo = new Label(left), hi = new Label(right); lo.getStyleClass().add("stereo-scale"); hi.getStyleClass().add("stereo-scale");
        HBox track = new HBox(10, lo, slider, hi); track.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(slider, Priority.ALWAYS);
        VBox box = new VBox(5, heading, track); box.setPadding(new Insets(8, 12, 8, 12)); box.setMinWidth(0);
        box.getStyleClass().add("stereo-trim"); return box;
    }
    public static double lowCutHz(double position) { return position <= 0 ? 0 : Math.min(5000, Math.round(20 * Math.pow(250, position) * 100) / 100.0); }
    public static double lowCutPosition(double hz) { return hz <= 0 ? 0 : Math.max(.000001, Math.log(hz / 20) / Math.log(250)); }
    public CheckBox enableBox() { return enabled; }
    public void onChange(Consumer<StereoImageSettings> handler) { onChange = handler; }
    public StereoImageSettings settings() {
        return new StereoImageSettings(generation.isSelected(), generationAmount.getValue()/100, lowCutHz(lowCut.getValue()),
                leveling.isSelected(), levelingAmount.getValue()/100, guard.isSelected(), margin.getValue(),
                profile.getValue(), reference.getValue()/100, sideGain.getValue());
    }
    public void apply(StereoImageSettings s) {
        applying = true;
        try {
            generation.setSelected(s.generation()); generationAmount.setValue(s.generationAmount()*100);
            lowCut.setValue(lowCutPosition(s.generatedLowCutHz())); sideGain.setValue(s.sideGainDb());
            leveling.setSelected(s.leveling()); levelingAmount.setValue(s.levelingAmount()*100);
            guard.setSelected(s.guard()); margin.setValue(s.guardMarginDb());
            profile.setValue(s.profile()); reference.setValue(s.referenceSideShare()*100);
        } finally { applying = false; }
        updateAvailability();
    }
    private void changed() { if (!applying && profile.getValue() != null) { updateAvailability(); onChange.accept(settings()); } }
    private void updateAvailability() {
        generationAmount.setDisable(!generation.isSelected()); lowCut.setDisable(!generation.isSelected()); lowValue.setDisable(!generation.isSelected());
        levelingAmount.setDisable(!leveling.isSelected()); margin.setDisable(!guard.isSelected());
        reference.setVisible(profile.getValue() == StereoProfile.REFERENCE); reference.setManaged(reference.isVisible());
        double hz = lowCutHz(lowCut.getValue()); lowValue.setText(hz == 0 ? "Off" : String.format(Locale.US,"%.0f Hz",hz));
        sideValue.setText(String.format(Locale.US,"%+.1f dB",sideGain.getValue()));
    }
    public void setStatus(String text) { status.setText(text); if (!text.isEmpty()) outputWarning = ""; }
    public void setOutputPeak(double dbtp) {
        outputWarning = dbtp > 0 ? "Output exceeds 0 dBTP. Reduce Amount / Side Gain or enable Limit / Peak Normalizer." : "";
    }
    public void acceptReference(double share) {
        StereoImageSettings s = settings();
        apply(new StereoImageSettings(s.generation(),s.generationAmount(),s.generatedLowCutHz(),s.leveling(),s.levelingAmount(),
                s.guard(),s.guardMarginDb(),StereoProfile.REFERENCE,share,s.sideGainDb())); onChange.accept(settings());
    }
    public void showPlan(StereoImageProcessor processor, double seconds) {
        StereoImageSettings selected = settings();
        if (!enabled.isSelected()) { target.setText("Stereo Image is bypassed"); clearMeters(); return; }
        if (selected.active() && (status.getText().startsWith("Preview") || status.getText().equals("Updating…"))) {
            target.setText("Updating stereo…"); clearMeters(); balance.setText("Preview · final meters pending"); return;
        }
        var plan = processor.plan();
        if (plan == null) { target.setText(selected.active() ? "Load audio to measure stereo" : "Select a section to process stereo"); clearMeters(); return; }
        double q = plan.targetSideShare();
        target.setText(selected.regulates() || selected.guard()
                ? String.format(Locale.US,"Target   Mid %.1f%% / Side %.1f%%",(1-q)*100,q*100)
                : selected.generates() ? "Stereo generation" : selected.sideGainDb() != 0 ? "Side gain" : "Select a section to process stereo");
        double measured = processor.measuredSideShare(seconds);
        balance.setText(Double.isFinite(measured) ? String.format(Locale.US,"Module output   Mid %.1f%% / Side %.1f%%",(1-measured)*100,measured*100) : "");
        energyBar.setProgress(Double.isFinite(measured) ? measured : 0);
        levelGain.setText(selected.regulates() ? gainText(plan.levelGainDb(seconds)) : "");
        guardGain.setText(selected.guard() ? gainText(plan.guardGainDb(seconds)) : "");
        String planNotice = selected.active() ? plan.notice() : "";
        notice.setText(planNotice.isEmpty() ? outputWarning : outputWarning.isEmpty() ? planNotice : planNotice + "\n" + outputWarning);
    }
    private void clearMeters() { balance.setText(""); levelGain.setText(""); guardGain.setText(""); notice.setText(""); energyBar.setProgress(0); }
    private static String gainText(double db) { return Math.abs(db) < .05 ? "" : String.format(Locale.US,"%+.1f dB",db); }
}
