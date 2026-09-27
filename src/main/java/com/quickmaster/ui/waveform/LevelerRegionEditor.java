package com.quickmaster.ui.waveform;

import com.quickmaster.processing.dynamics.macro.LevelerExclusions;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import java.util.Locale;
import java.util.function.*;

/** Dedicated gesture mode. Never seeks, selects a loop or edits PCM. */
public final class LevelerRegionEditor {
    private final Canvas canvas;
    private final Supplier<WaveformViewport> viewport;
    private final Supplier<LevelerExclusions> model;
    private final IntSupplier rate;
    private final LongSupplier frames;
    private final Consumer<LevelerExclusions> commit;
    private final Runnable repaint;
    private final ToggleButton edit=new ToggleButton("Exclude regions");
    private final Button clear=new Button("Clear all");
    private final Tooltip hover=new Tooltip();
    private final VBox controls;
    private LevelerExclusions preview;
    private boolean dragging;
    private long anchor, moving;
    private int resizing=-1;
    private boolean ready, pending;
    private ContextMenu menu;

    public LevelerRegionEditor(Canvas canvas,Supplier<WaveformViewport> viewport,
            Supplier<LevelerExclusions> model,IntSupplier rate,LongSupplier frames,
            Consumer<LevelerExclusions> commit,Runnable repaint) {
        this.canvas=canvas;this.viewport=viewport;this.model=model;this.rate=rate;
        this.frames=frames;this.commit=commit;this.repaint=repaint;
        edit.setId("leveler-exclude-regions");clear.setId("leveler-clear-regions");
        edit.getStyleClass().add("leveler-exclude-toggle");
        edit.setTooltip(new Tooltip("Paint regions on the waveform that the Leveler must leave unchanged. Other effects still apply."));
        clear.setTooltip(new Tooltip("Remove all Leveler exclusions for this track. Undo restores them."));
        edit.setOnAction(e->{cancelDrag();refresh();repaint.run();});
        clear.setOnAction(e->{cancelDrag();commit.accept(LevelerExclusions.EMPTY);});
        HBox buttons=new HBox(7,edit,clear);buttons.setAlignment(Pos.CENTER);
        controls=new VBox(0,buttons);controls.setAlignment(Pos.CENTER);
        Tooltip.install(canvas,hover);
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED,this::moved);
        refresh();
    }
    public VBox controls() { return controls; }
    public boolean isEditing() { return edit.isSelected(); }
    public void update(boolean ready,boolean pending) { this.ready=ready;this.pending=pending;refresh(); }
    public void resetGesture() { cancelDrag();if(menu!=null)menu.hide();edit.setSelected(false);refresh(); }
    public boolean escape() {
        if(dragging)cancelDrag();else if(edit.isSelected())edit.setSelected(false);else return false;
        refresh();repaint.run();return true;
    }
    private void cancelDrag() { dragging=false;preview=null;resizing=-1; }
    private void refresh() {
        edit.setDisable(!ready);clear.setDisable(!ready||model.get().regions().isEmpty());
        int n=model.get().regions().size();
        edit.setText(n==0?"Exclude regions":"Exclude regions ("+n+")");
        edit.setAccessibleText(pending?"Applying exclusions":n+" excluded regions. Paint exclusions on the waveform.");
        canvas.setCursor(edit.isSelected()?Cursor.CROSSHAIR:Cursor.DEFAULT);
    }
    private long frame(double x) {
        double seconds=viewport.get().timeAtX(x,canvas.getWidth()).orElse(0);
        return Math.max(0,Math.min(frames.getAsLong(),Math.round(seconds*rate.getAsInt())));
    }
    private double x(long frame) { return viewport.get().xAtTime(frame/(double)Math.max(1,rate.getAsInt()),canvas.getWidth()).orElse(0); }
    private int hit(long frame) {
        var regions=model.get().regions();
        for(int i=0;i<regions.size();i++)if(frame>=regions.get(i).start()&&frame<regions.get(i).end())return i;
        return -1;
    }
    public boolean press(MouseEvent e) {
        if(!ready)return false;
        if(menu!=null)menu.hide();
        if(e.getButton()==MouseButton.SECONDARY) {
            int index=hit(frame(e.getX()));
            if(index>=0) {
                var identity=model.get();
                MenuItem bounds=new MenuItem("Edit exclusion times…");
                bounds.setOnAction(a->{if(model.get().equals(identity))editTimes(index);});
                MenuItem remove=new MenuItem("Remove exclusion");
                remove.setOnAction(a->{if(model.get().equals(identity))commit.accept(identity.remove(index));});
                menu=new ContextMenu(bounds,remove);menu.show(canvas,e.getScreenX(),e.getScreenY());
            }
            e.consume();return true;
        }
        if(!isEditing()||e.getButton()!=MouseButton.PRIMARY)return false;
        anchor=moving=frame(e.getX());resizing=-1;
        double nearest=9;
        var regions=model.get().regions();
        for(int i=0;i<regions.size();i++) {
            var r=regions.get(i);
            double a=Math.abs(x(r.start())-e.getX()),b=Math.abs(x(r.end())-e.getX());
            if(a<nearest){nearest=a;resizing=i;anchor=r.end();moving=r.start();}
            if(b<nearest){nearest=b;resizing=i;anchor=r.start();moving=r.end();}
        }
        dragging=true;preview=model.get();e.consume();return true;
    }
    public boolean drag(MouseEvent e) {
        if(!dragging)return false;
        moving=frame(e.getX());
        var base=resizing<0?model.get():model.get().remove(resizing);
        try { preview=base.add(Math.min(anchor,moving),Math.max(anchor,moving)); }
        catch(IllegalArgumentException limit) { preview=model.get();hover.setText("Maximum 256 excluded regions. Remove a region before adding another."); }
        repaint.run();e.consume();return true;
    }
    public boolean release(MouseEvent e) {
        if(!dragging)return false;
        var result=preview;
        boolean changed=Math.abs(moving-anchor)>=Math.max(1,rate.getAsInt()/100);
        cancelDrag();
        if(changed&&!result.equals(model.get()))commit.accept(result);
        repaint.run();e.consume();return true;
    }
    private void moved(MouseEvent e) {
        int index=hit(frame(e.getX()));
        if(index>=0) {
            var r=model.get().regions().get(index);
            hover.setText(String.format(Locale.US,"Excluded from Leveler · %.3f–%.3f s\nRight-click to edit or remove. Other effects still apply.",r.start()/(double)rate.getAsInt(),r.end()/(double)rate.getAsInt()));
        } else hover.setText(isEditing()?"Drag to exclude from Leveler. Esc to finish. Wheel to pan; Ctrl/⌘ + wheel to zoom.":"Click to seek. Shift-drag to select for trim. Wheel to pan; Ctrl/⌘ + wheel to zoom.");
        boolean handle=isEditing()&&model.get().regions().stream().anyMatch(r->Math.abs(x(r.start())-e.getX())<9||Math.abs(x(r.end())-e.getX())<9);
        canvas.setCursor(handle?Cursor.H_RESIZE:isEditing()?Cursor.CROSSHAIR:Cursor.DEFAULT);
    }
    private void editTimes(int index) {
        var current=model.get();var r=current.regions().get(index);
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("Edit Leveler exclusion");
        if(canvas.getScene()!=null)dialog.initOwner(canvas.getScene().getWindow());
        if(canvas.getScene()!=null)dialog.getDialogPane().getStylesheets().addAll(canvas.getScene().getStylesheets());
        dialog.getDialogPane().setStyle("-fx-background-color: #232329;");
        TextField start=new TextField(String.format(Locale.US,"%.3f",r.start()/(double)rate.getAsInt()));
        TextField end=new TextField(String.format(Locale.US,"%.3f",r.end()/(double)rate.getAsInt()));
        Label error=new Label();error.setStyle("-fx-text-fill: #ff858b;");
        dialog.getDialogPane().setContent(new VBox(7,new Label("Start (seconds)"),start,new Label("End (seconds)"),end,error));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY,ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.APPLY).addEventFilter(javafx.event.ActionEvent.ACTION,e->{
            try {
                double a=Double.parseDouble(start.getText().trim()),b=Double.parseDouble(end.getText().trim());
                if(!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<=a||b>frames.getAsLong()/(double)rate.getAsInt())throw new NumberFormatException();
                long first=Math.round(a*rate.getAsInt()),last=Math.round(b*rate.getAsInt());
                if(last<=first)throw new NumberFormatException();
                if(model.get().equals(current))commit.accept(current.remove(index).add(first,last));
            }catch(NumberFormatException ex){error.setText("Enter valid times within the track, with end after start.");e.consume();}
        });
        dialog.showAndWait();
    }
    public void draw(GraphicsContext gc,double w,double h) {
        gc.save();
        var display=preview!=null?preview:model.get();
        for(var r:display.regions()) {
            double left=x(r.start()),right=x(r.end());
            if(right<0||left>w)continue;
            double a=Math.max(0,left),b=Math.min(w,right);
            gc.setFill(Color.web("#f05c68",.20));gc.fillRect(a,23,b-a,h-42);
            gc.setStroke(Color.web("#ff858b"));gc.setLineWidth(1.5);
            gc.strokeLine(a,24,b,24);
            if(left>=0){gc.strokeLine(left,24,left,h-20);if(isEditing())gc.fillRect(left-3,h/2-10,6,20);}
            if(right<=w){gc.strokeLine(right,24,right,h-20);if(isEditing())gc.fillRect(right-3,h/2-10,6,20);}
            if(b-a>95){gc.setFill(Color.web("#ffc2c6"));gc.setFont(Font.font(10));gc.fillText("LEVELER EXCLUDED",a+6,38,b-a-12);}
        }
        if(isEditing()) {
            gc.setFill(Color.web("#391e26"));gc.fillRect(0,h-20,w,20);
            gc.setFill(Color.web("#ffc2c6"));gc.setFont(Font.font(11));
            gc.fillText("LEVELER EXCLUSIONS  ·  Drag to protect a region  ·  Drag edges to resize  ·  Esc to finish",8,h-6,w-16);
        }
        gc.restore();
    }
}
