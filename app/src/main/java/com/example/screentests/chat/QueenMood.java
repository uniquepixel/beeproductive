package com.example.screentests.chat;

/**
 * Emotional states the Queen Bee can display. Each maps to a drawable in the overlay
 * (see OverlayService#moodToDrawable). THINKING is set automatically whenever a network
 * call is in flight; every other mood is carried in {@link QueenBeeUiState} so the backend
 * can drive the Queen's expression on the same LiveData basis as the rest of the chat state.
 */
public enum QueenMood {
    SAD,
    HAPPY,
    TALKING_1,
    TALKING_2,
    SHOWING_HONEY,
    EXCLAIMING,
    ASKING,
    THINKING
}
