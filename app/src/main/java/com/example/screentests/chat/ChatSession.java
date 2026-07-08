//This class is mostly for information passing and datawrapping
package com.example.screentests.chat;


import com.example.screentests.network.ChatRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * One Queen Bee conversation. Holds the full transcript that is sent to the
 * model on every turn (the system prompt is element 0), plus a snapshot of
 * the unproductivity score at the moment the session started.
 *
 * Sessions live in memory only (see {@link QueenBeeChatManager}); they are
 * not persisted across process death.
 */
public class ChatSession {
    public final String sessionId;
    public final long createdAt;
    public final int scoreSnapshot;

    /** Full transcript sent to the model: index 0 is the "system" prompt. */
    final List<ChatRequest.Message> messages = new ArrayList<>();

    // Screenshot evidence the Queen holds against the user for this session. Captured (or loaded
    // from the last log) when the session starts and pushed to the frontend via QueenBeeUiState.
    private String evidenceScreenshotBase64;
    private String evidenceSummary;
    private boolean screenshotRevealed = false;

    ChatSession(String sessionId, long createdAt, int scoreSnapshot, String systemPrompt) {
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.scoreSnapshot = scoreSnapshot;
        this.messages.add(new ChatRequest.Message("system", systemPrompt));
    }

    /** Replaces the system prompt (element 0) once async context is ready. */
    synchronized void setSystemPrompt(String prompt) {
        messages.set(0, new ChatRequest.Message("system", prompt));
    }

    synchronized void addUser(String text) {
        messages.add(new ChatRequest.Message(ChatMessage.ROLE_USER, text));
    }

    synchronized void addAssistant(String text) {
        messages.add(new ChatRequest.Message(ChatMessage.ROLE_ASSISTANT, text));
    }

    /** Snapshot of the message list for the network call (avoids mutation races). */
    synchronized List<ChatRequest.Message> snapshotMessages() {
        return new ArrayList<>(messages);
    }

    /** Stores the screenshot evidence for this session. AI generated */
    synchronized void setEvidence(String screenshotBase64, String summary) {
        this.evidenceScreenshotBase64 = screenshotBase64;
        this.evidenceSummary = summary;
    }

    /** Base64 JPEG of the evidence screenshot, null when none exists. AI generated */
    public synchronized String getEvidenceScreenshot() {
        return evidenceScreenshotBase64;
    }

    /** AI summary of the evidence screenshot, null when none exists. AI generated */
    public synchronized String getEvidenceSummary() {
        return evidenceSummary;
    }

    /** Marks the screenshot as presented; it stays visible for the rest of the session. AI generated */
    synchronized void markScreenshotRevealed() {
        this.screenshotRevealed = true;
    }

    /** True once the Queen has presented the screenshot to the user. AI generated */
    public synchronized boolean isScreenshotRevealed() {
        return screenshotRevealed;
    }

    /**
     * The conversation as the frontend should render it: user and assistant
     * turns only, in order. The system prompt is intentionally excluded.
     */
    public synchronized List<ChatMessage> getHistory() {
        List<ChatMessage> history = new ArrayList<>();
        for (ChatRequest.Message m : messages) {
            if ("system".equals(m.role)) continue;
            if (m.content instanceof String) {
                history.add(new ChatMessage(m.role, (String) m.content, createdAt));
            }
        }
        return history;
    }
}
