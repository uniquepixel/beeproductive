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

    public QueenBeeUiState(QueenMood mood, boolean thinking, Speaker speaker,
                           String text, Decision decision) {
        this.mood = mood;
        this.thinking = thinking;
        this.speaker = speaker;
        this.text = text != null ? text : "";
        this.decision = decision != null ? decision : Decision.NONE;
    }
    
    public static QueenBeeUiState idle() {
        return new QueenBeeUiState(QueenMood.TALKING_1, false, Speaker.NONE, "", Decision.NONE);
    }
}
