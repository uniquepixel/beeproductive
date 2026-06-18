package com.example.screentests.network;

import java.util.List;

/**
 * Response body from the OpenRouter chat-completions endpoint.
 * The assistant reply text lives at choices[0].message.content.
 */
public class ChatResponse {
    public List<Choice> choices;

    public static class Choice {
        public Message message;
    }

    public static class Message {
        public String role;
        public String content;
    }

    /** Returns the first assistant message text, or null if absent. */
    public String getFirstText() {
        if (choices != null && !choices.isEmpty()) {
            Choice first = choices.get(0);
            if (first.message != null && first.message.content != null) {
                return first.message.content;
            }
        }
        return null;
    }
}
