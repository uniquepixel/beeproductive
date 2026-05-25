package com.example.screentests.network;

import java.util.List;

public class GeminiResponse {
    public List<Candidate> candidates;

    public static class Candidate {
        public com.example.screentests.network.GeminiRequest.Content content;
    }

    public String getFirstText() {
        if (candidates != null && !candidates.isEmpty()) {
            Candidate firstCandidate = candidates.get(0);
            if (firstCandidate.content != null && firstCandidate.content.parts != null && !firstCandidate.content.parts.isEmpty()) {
                return firstCandidate.content.parts.get(0).text;
            }
        }
        return null; // or empty string
    }
}
