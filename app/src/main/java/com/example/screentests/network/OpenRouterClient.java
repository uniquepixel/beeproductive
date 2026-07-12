package com.example.screentests.network;

import android.util.Log;

import com.example.screentests.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Single client for all OpenRouter calls. Free models are used:
 *   - CHAT_MODEL   drives the Queen Bee conversation (text only).
 *   - VISION_MODEL turns a screenshot into a one-sentence summary.
 *
 * The OpenRouter API key is injected at build time from local.properties
 * via BuildConfig.OPENROUTER_API_KEY (never hardcode it here).
 */
public class OpenRouterClient {
    private static final String TAG = "OpenRouterClient";
    private static final String BASE_URL = "https://openrouter.ai/";

    public static final String CHAT_MODEL = "nvidia/nemotron-3-super-120b-a12b:free";
    // Nemotron is text-only, so screenshots go through a free vision model.
    // Swap this for any vision-capable OpenRouter model id if availability changes.
    public static final String VISION_MODEL = "meta-llama/llama-3.2-11b-vision-instruct:free";

    private static OpenRouterClient instance;
    private final OpenRouterApi api;

    private OpenRouterClient() {
        // AI-changed: OkHttp's default 10s read timeout regularly cut off the Queen's opening
        // line — the free-tier models often need well over 10s to answer the big system prompt
        // (and the vision model to describe a screenshot), so the very first message frequently
        // died with a timeout. Give the models room to answer.
        okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        api = retrofit.create(OpenRouterApi.class);
    }

    public static synchronized OpenRouterClient getInstance() {
        if (instance == null) {
            instance = new OpenRouterClient();
        }
        return instance;
    }

    public interface Callback {
        void onSuccess(String textResponse);
        void onError(String error);
    }

    /**
     * Sends a base64 JPEG to the vision model and returns a short description.
     */
    public void analyzeScreenshot(String base64Image, Callback callback) {
        if (base64Image == null || base64Image.isEmpty()) {
            callback.onError("Empty image");
            return;
        }

        List<ChatRequest.ContentPart> parts = new ArrayList<>();
        parts.add(ChatRequest.ContentPart.text(
                "Describe in one brief sentence what the user is doing on this screen."));
        parts.add(ChatRequest.ContentPart.image("data:image/jpeg;base64," + base64Image));

        ChatRequest.Message message = new ChatRequest.Message("user", parts);
        ChatRequest request = new ChatRequest(VISION_MODEL,
                new ArrayList<>(Arrays.asList(message)));

        send(request, callback, "No description provided.");
    }

    /**
     * Runs a multi-turn chat against the chat model. The caller owns the
     * full message list (system prompt first, then alternating turns).
     */
    public void chat(List<ChatRequest.Message> messages, Callback callback) {
        if (messages == null || messages.isEmpty()) {
            callback.onError("No messages");
            return;
        }
        ChatRequest request = new ChatRequest(CHAT_MODEL, messages);
        send(request, callback, "");
    }

    private void send(ChatRequest request, Callback callback, String fallbackText) {
        String bearer = "Bearer " + BuildConfig.OPENROUTER_API_KEY;
        api.chatCompletion(bearer, request).enqueue(new retrofit2.Callback<ChatResponse>() {
            @Override
            public void onResponse(retrofit2.Call<ChatResponse> call, retrofit2.Response<ChatResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String result = response.body().getFirstText();
                    callback.onSuccess(result != null ? result : fallbackText);
                } else {
                    callback.onError("Request failed: " + response.code());
                }
            }

            @Override
            public void onFailure(retrofit2.Call<ChatResponse> call, Throwable t) {
                Log.e(TAG, "API call failed", t);
                callback.onError(t.getMessage());
            }
        });
    }
}
