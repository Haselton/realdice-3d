package com.haselton.realcoin3d;

public final class CoinDefinition {
    public final String id, name, category, era, metal, headsMark, tailsMark, note;
    public final double diameterMm, thicknessMm, weightGrams;
    public final int baseColor, highlightColor, shadowColor;

    public CoinDefinition(String id, String name, String category, String era, String metal,
                          double diameterMm, double thicknessMm, double weightGrams,
                          String headsMark, String tailsMark, String note,
                          int baseColor, int highlightColor, int shadowColor) {
        this.id=id; this.name=name; this.category=category; this.era=era; this.metal=metal;
        this.diameterMm=diameterMm; this.thicknessMm=thicknessMm; this.weightGrams=weightGrams;
        this.headsMark=headsMark; this.tailsMark=tailsMark; this.note=note;
        this.baseColor=baseColor; this.highlightColor=highlightColor; this.shadowColor=shadowColor;
    }
}
