package com.example.screentests.network;

import java.util.List;

/**
 * Request body for the OpenRouter chat-completions endpoint
 * (OpenAI-style). The same shape carries plain text chat and
 * multimodal (vision) requests:
 *
 *   - For text chat, a Message's {@code content} is a String.
 *   - For vision, {@code content} is a List<ContentPart> mixing a
 *     text part and an image_url part.
 *
 * Gson serializes the {@code Object content} field as a JSON string
 * or a JSON array depending on the runtime type, and omits null
 * fields, so unused ContentPart fields disappear from the payload.
 */
public class ChatRequest {
    public String model;
    public List<Message> messages;

    public ChatRequest(String model, List<Message> messages) {
        this.model = model;
        this.messages = messages;
    }

    public static class Message {
        public String role;     // "system" | "user" | "assistant"
        public Object content;  // String (chat) or List<ContentPart> (vision)

        public Message(String role, Object content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class ContentPart {
        public String type;          // "text" | "image_url"
        public String text;          // set when type == "text"
        public ImageUrl image_url;   // set when type == "image_url"

        public static ContentPart text(String text) {
            ContentPart part = new ContentPart();
            part.type = "text";
            part.text = text;
            return part;
        }

        public static ContentPart image(String dataUrl) {
            ContentPart part = new ContentPart();
            part.type = "image_url";
            part.image_url = new ImageUrl(dataUrl);
            return part;
        }
    }

    public static class ImageUrl {
        public String url;   // e.g. "data:image/jpeg;base64,...."

        public ImageUrl(String url) {
            this.url = url;
        }
    }
}
