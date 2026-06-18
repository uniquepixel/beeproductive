package com.example.screentests.network;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface OpenRouterApi {
    @POST("api/v1/chat/completions")
    Call<ChatResponse> chatCompletion(
        @Header("Authorization") String bearerToken,
        @Body ChatRequest request
    );
}
