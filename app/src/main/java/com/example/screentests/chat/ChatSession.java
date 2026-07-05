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
