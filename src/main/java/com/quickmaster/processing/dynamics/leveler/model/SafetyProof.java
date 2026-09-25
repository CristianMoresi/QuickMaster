package com.quickmaster.processing.dynamics.leveler.model;
/** Immutable result of an actual full finite true-peak scan, never a tile estimate. */
public final class SafetyProof {
    private final SafetyStatus status;
    private final double inputTp,candidateTp,ceilingLinear,boostScale;
    private final int candidatePasses;
    public SafetyProof(SafetyStatus status,double inputTp,double candidateTp,double ceilingLinear,double boostScale,int candidatePasses) {
        if(status==null||!Double.isFinite(inputTp)||!Double.isFinite(candidateTp)||!Double.isFinite(ceilingLinear)||!Double.isFinite(boostScale)
                ||inputTp<0||candidateTp<0||ceilingLinear<0||ceilingLinear>1||boostScale<0||boostScale>1||candidatePasses<0
                ||((status==SafetyStatus.PROVEN||status==SafetyStatus.BOOST_REDUCED)&&(candidatePasses==0||candidateTp>ceilingLinear)))
            throw new IllegalArgumentException("Invalid safety proof.");
        this.status=status;this.inputTp=inputTp;this.candidateTp=candidateTp;this.ceilingLinear=ceilingLinear;this.boostScale=boostScale;this.candidatePasses=candidatePasses;
    }
    public boolean proven(){return status==SafetyStatus.PROVEN||status==SafetyStatus.BOOST_REDUCED;}
    public SafetyStatus status(){return status;}
    public double inputTp(){return inputTp;}
    public double candidateTp(){return candidateTp;}
    public double ceilingLinear(){return ceilingLinear;}
    public double boostScale(){return boostScale;}
    public int candidatePasses(){return candidatePasses;}
}
