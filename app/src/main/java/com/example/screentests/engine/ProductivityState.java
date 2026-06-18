package com.example.screentests.engine;

public class ProductivityState {
    private final int score; // 0 to 100
    private final int level; // Computed: 0 to 4 based on score
    private final boolean showInterventionOverlay;
    private final String currentPackageName;
    private final boolean checkRequiredForUnknownApp;
    private final boolean aiConsentRequired;
    private final boolean showQueenBeeChat;
    private final String queenBeeSessionId;

    public ProductivityState(int score, int level, boolean showInterventionOverlay, String currentPackageName,
                             boolean checkRequiredForUnknownApp, boolean aiConsentRequired,
                             boolean showQueenBeeChat, String queenBeeSessionId) {
        this.score = score;
        this.level = level;
        this.showInterventionOverlay = showInterventionOverlay;
        this.currentPackageName = currentPackageName != null ? currentPackageName : "";
        this.checkRequiredForUnknownApp = checkRequiredForUnknownApp;
        this.aiConsentRequired = aiConsentRequired;
        this.showQueenBeeChat = showQueenBeeChat;
        this.queenBeeSessionId = queenBeeSessionId;
    }

    public static ProductivityState initial() {
        return new ProductivityState(0, 0, false, "", false, false, false, null);
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

    public boolean isAiConsentRequired() {
        return aiConsentRequired;
    }

    /** True when the score hit the max and the Queen Bee chat should open. */
    public boolean isShowQueenBeeChat() {
        return showQueenBeeChat;
    }

    /** Id of the active Queen Bee session (null when no chat is active). */
    public String getQueenBeeSessionId() {
        return queenBeeSessionId;
    }

    public ProductivityState copyWith(int score, int level, boolean showOverlay, String packageName,
                                      boolean checkRequired, boolean aiConsentRequired,
                                      boolean showQueenBeeChat, String queenBeeSessionId) {
        return new ProductivityState(score, level, showOverlay, packageName, checkRequired,
                aiConsentRequired, showQueenBeeChat, queenBeeSessionId);
    }
}
