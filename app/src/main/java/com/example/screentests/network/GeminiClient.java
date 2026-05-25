package com.example.screentests.network;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.ArrayList;
import java.util.List;
import android.util.Log;

public class GeminiClient {
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/";
    // In production, NEVER hardcode API keys! This should be injected at build time or fetched securely.
    private static final String API_KEY = "YOUR_API_KEY_HERE"; 
    private static GeminiClient instance;
    private GeminiApi api;

    private GeminiClient() {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        api = retrofit.create(GeminiApi.class);
    }

    public static synchronized GeminiClient getInstance() {
        if (instance == null) {
            instance = new GeminiClient();
        }
        return instance;
    }

    public interface GeminiCallback {
        void onSuccess(String textResponse);
        void onError(String error);
    }

    public void analyzeScreenshot(String base64Image, GeminiCallback callback) {
        if (base64Image == null || base64Image.isEmpty()) {
            callback.onError("Empty image");
            return;
        }

        List<GeminiRequest.Part> parts = new ArrayList<>();
        parts.add(new GeminiRequest.Part("Describe in one brief sentence what the user is doing on this screen."));
        parts.add(new GeminiRequest.Part(new GeminiRequest.InlineData("image/jpeg", base64Image)));

        GeminiRequest.Content content = new GeminiRequest.Content(parts);
        List<GeminiRequest.Content> contents = new ArrayList<>();
        contents.add(content);

        GeminiRequest request = new GeminiRequest(contents);

        api.generateContent(API_KEY, request).enqueue(new retrofit2.Callback<GeminiResponse>() {
            @Override
            public void onResponse(retrofit2.Call<GeminiResponse> call, retrofit2.Response<GeminiResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String result = response.body().getFirstText();
                    callback.onSuccess(result != null ? result : "No description provided by Gemini.");
                } else {
                    callback.onError("Request failed: " + response.code());
                }
            }

            @Override
            public void onFailure(retrofit2.Call<GeminiResponse> call, Throwable t) {
                Log.e("GeminiClient", "API call failed", t);
                callback.onError(t.getMessage());
            }
        });
    }
}
