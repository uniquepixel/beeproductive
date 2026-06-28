package com.example.screentests.chat;

/**
 * Immutable snapshot of the Queen Bee chat UI, broadcast via
 * {@link QueenBeeChatManager#getUiState()} (a LiveData, mirroring
 * {@code ProductivityEngine.getState()}). The intervention overlay observes this and renders
 * the single most-recent dialogue line, the Queen's mood image, and any final decision.
 *
 * Today the frontend (the manager, standing in for the backend) sets these fields from the
 * network lifecycle and the decision token/timeout. Later the backend can post to the same
 * LiveData to drive mood and the decision directly — see
 * docs/QUEENBEE_UI_STATE_INTEGRATION.md.
 */
public class QueenBeeUiState {

    /** Who said the {@link #text} line currently shown in the big box. */
    public enum Speaker { NONE, USER, QUEEN }

    /** Final outcome of the conversation. NONE until the Queen (or the timeout) decides. */
    public enum Decision { NONE, REFILL, KICK }

    public final QueenMood mood;
    public final boolean thinking;     // true while the AI is generating a reply
    public final Speaker speaker;      // who owns the visible line
    public final String text;          // the single last line of dialogue
    public final Decision decision;    // NONE until a refill/kick is decided

    public QueenBeeUiState(QueenMood mood, boolean thinking, Speaker speaker,
                           String text, Decision decision) {
        this.mood = mood;
        this.thinking = thinking;
        this.speaker = speaker;
        this.text = text != null ? text : "";
        this.decision = decision != null ? decision : Decision.NONE;
    }

    /** Neutral starting state (no active conversation). */
    public static QueenBeeUiState idle() {
        return new QueenBeeUiState(QueenMood.TALKING_1, false, Speaker.NONE, "", Decision.NONE);
    }
}
