package com.quickmaster.processing.dynamics.leveler.model;
import com.quickmaster.processing.dynamics.SparseGainSchedule;
public final class SafetyResult {
    private final SparseGainSchedule schedule;
    private final SafetyProof proof;
    public SafetyResult(SparseGainSchedule schedule,SafetyProof proof) {
        if(schedule==null||proof==null)throw new IllegalArgumentException("Complete safety result required.");
        this.schedule=schedule;this.proof=proof;
    }
    public SparseGainSchedule schedule(){return schedule;}
    public SafetyProof proof(){return proof;}
}
