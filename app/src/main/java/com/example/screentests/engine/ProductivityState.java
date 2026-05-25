package com.example.screentests.engine;

public class ProductivityState {
    private final int score; // 0 to 100
    private final int level; // Computed: 0 to 4 based on score
    private final boolean showInterventionOverlay;
    private final String currentPackageName;
    private final boolean checkRequiredForUnknownApp;
    private final boolean geminiConsentRequired;

    public ProductivityState(int score, int level, boolean showInterventionOverlay, String currentPackageName, boolean checkRequiredForUnknownApp, boolean geminiConsentRequired) {
        this.score = score;
        this.level = level;
        this.showInterventionOverlay = showInterventionOverlay;
        this.currentPackageName = currentPackageName != null ? currentPackageName : "";
        this.checkRequiredForUnknownApp = checkRequiredForUnknownApp;
        this.geminiConsentRequired = geminiConsentRequired;
    }

    public static ProductivityState initial() {
        return new ProductivityState(0, 0, false, "", false, false);
    }

    public int getScore() {
        return score;
    }

    public int getLevel() {
        return level;
    }

    public boolean isShowInterventionOverlay() {
        return showInterventionOverlay;
    }

    public String getCurrentPackageName() {
        return currentPackageName;
    }

    public boolean isCheckRequiredForUnknownApp() {
        return checkRequiredForUnknownApp;
    }

    public boolean isGeminiConsentRequired() {
        return geminiConsentRequired;
    }

    public ProductivityState copyWith(int score, int level, boolean showOverlay, String packageName, boolean checkRequired, boolean geminiConsentRequired) {
        return new ProductivityState(score, level, showOverlay, packageName, checkRequired, geminiConsentRequired);
    }
}