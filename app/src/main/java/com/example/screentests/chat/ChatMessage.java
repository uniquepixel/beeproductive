//This class is just a datawrapper
package com.example.screentests.chat;

public class ChatMessage {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public final String role;   // "user" | "assistant"
    public final String text;
    public final long timestamp;

    public ChatMessage(String role, String text, long timestamp) {
        this.role = role;
        this.text = text;
        this.timestamp = timestamp;
    }
}
