package com.example.screentests.network;

import java.util.List;

public class GeminiRequest {
    public List<Content> contents;

    public GeminiRequest(List<Content> contents) {
        this.contents = contents;
    }

    public static class Content {
        public List<Part> parts;

        public Content(List<Part> parts) {
            this.parts = parts;
        }
    }

    public static class Part {
        public String text;
        public InlineData inlineData;

        public Part(String text) {
            this.text = text;
        }

        public Part(InlineData inlineData) {
            this.inlineData = inlineData;
        }

        public Part(String text, InlineData inlineData) {
            this.text = text;
            this.inlineData = inlineData;
        }
    }

    public static class InlineData {
        public String mimeType;
        public String data;

        public InlineData(String mimeType, String data) {
            this.mimeType = mimeType;
            this.data = data;
        }
    }
}
