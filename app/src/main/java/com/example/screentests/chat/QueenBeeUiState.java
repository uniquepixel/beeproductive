package com.example.screentests.chat;

/**
 * Non changeable snapshot of the Queen Bee chat UI, broadcast via
 * {@link QueenBeeChatManager#getUiState()} (LiveData, as
 * {@code ProductivityEngine.getState()}). The intervention overlay observes this
 *
 * Rn the frontend (the manager, standing in for the backend) sets these fields from the
 * network + decision token/timeout.*/
public class QueenBeeUiState {

    public enum Speaker { NONE, USER, QUEEN }

    public enum Decision { NONE, REFILL, KICK }

    public final QueenMood mood;
    public final boolean thinking;     //true while AI is generating
    public final Speaker speaker;      //who owns the box
    public final String text;          //box content
    public final Decision decision;    //NONE until a refill/kick is decided
    //Screenshot evidence the Queen is holding up. Carried in the same LiveData channel so the
    //frontend never has to pull it from the database itself.
    public final boolean showScreenshot;      //true once the Queen presents her evidence
    public final String screenshotBase64;     //base64 JPEG, null when none is available
    public final String screenshotCaption;    //AI summary of the screenshot, null when none

    //Partially AI generated / Modified by AI (screenshot evidence fields added)
    public QueenBeeUiState(QueenMood mood, boolean thinking, Speaker speaker,
                           String text, Decision decision,
                           boolean showScreenshot, String screenshotBase64, String screenshotCaption) {
        this.mood = mood;
        this.thinking = thinking;
        this.speaker = speaker;
        this.text = text != null ? text : "";
        this.decision = decision != null ? decision : Decision.NONE;
        this.showScreenshot = showScreenshot;
        this.screenshotBase64 = screenshotBase64;
        this.screenshotCaption = screenshotCaption;
    }

    /** Convenience constructor for states without screenshot evidence. AI generated */
    public QueenBeeUiState(QueenMood mood, boolean thinking, Speaker speaker,
                           String text, Decision decision) {
        this(mood, thinking, speaker, text, decision, false, null, null);
    }

    public static QueenBeeUiState idle() {
        return new QueenBeeUiState(QueenMood.TALKING_1, false, Speaker.NONE, "", Decision.NONE);
    }
}
