package com.quickmaster.processing.dynamics.leveler.model;
public final class ControlState {
    private final double leveling,speed;
    public ControlState(double leveling,double speed) {
        if(!Double.isFinite(leveling)||!Double.isFinite(speed))throw new IllegalArgumentException("Finite controls required.");
        this.leveling=Math.max(0,Math.min(1,leveling));this.speed=Math.max(0,Math.min(1,speed));
    }
    public double leveling(){return leveling;}
    public double speed(){return speed;}
}
