# BeeProductive - Backend Architecture & Frontend Integration

This document explains the backend architecture for the BeeProductive app and how the frontend UI can interact with it.

## Architecture Overview

### 1. The Productivity Engine (The Brain)
The `ProductivityEngine` is a Java Singleton that manages a global scoring system (0 to 100).
- It runs a background timer every 1 minute.
- If the user is in an **UNPRODUCTIVE** app, the score increases based on the app's predefined *Severity* (1 = +5, 2 = +10, 3 = +20).
- If the user is in a **PRODUCTIVE** app, the score decreases (Cooldown: -15).
- The score dictates the `level` (0 to 4), which controls the bees on the screen and triggers the Gemini Intervention Overlay.

### 2. TrackerAccessibilityService (The Eyes & Hands)
We use an Android `AccessibilityService` (requiring Android 11 / API 30+).
- It stealthily tracks which app the user currently has opened via `TYPE_WINDOW_STATE_CHANGED`.
- It can take screenshots completely in the background without prompting the user over and over.
- If a user tries to open a globally `BLOCKED` app (e.g., blocked by Gemini bot), it simulates a HOME button press, kicking them out immediately.

### 3. Gemini REST API (The Analyzer)
To remain 100% Java-compatible and avoid heavy Kotlin Coroutines requirements, we interact with the Gemini 1.5 Flash API via native Retrofit REST calls (`GeminiClient`). It sends Base64 screenshots and expects a 1-sentence summary of the user's activity.

### 4. Room Database (The Memory)
- **AppPolicy**: Stores if an app is PRODUCTIVE, UNPRODUCTIVE, UNKNOWN, or BLOCKED, along with its penalty severity.
- **ActivityLog**: Every X minutes while in an unproductive app, a screenshot is analyzed by Gemini and stored here. This serves as the "chat history" when the user reaches Level 4 and has to justify their procrastination.

---

## Frontend Integration Guide

Here is how you connect your UI/Frontend to this backend engine.

### Step 1: Initialization
Call this once when the application starts (e.g., in your `MainActivity` or custom `Application` class):

```java
ProductivityEngine.getInstance().init(getApplicationContext());
```

### Step 2: Observing the State
In your Activity or Fragment, observe the `LiveData` to update your UI (e.g., spawn bees, show popups):

```java
ProductivityEngine.getInstance().getState().observe(this, state -> {
    // 1. The Unproductivity Level (0 to 4)
    // Use this to spawn progressive bees on the screen.
    int currentLevel = state.getLevel(); 

    // 2. Intervention Trigger
    // If true, level is 4. Show the full-screen chat with Gemini!
    boolean showOverlay = state.isShowInterventionOverlay();

    // 3. Unknown App Detection
    // If true, the user opened an app that is not in the database yet.
    // Prompt the user to categorize it (Productive/Unproductive + Severity)!
    boolean askUser = state.isCheckRequiredForUnknownApp();
    
    // 4. Gemini Consent Detection
    // If true, the user hasn't explicitly allowed Gemini to analyze this app yet.
    // Prompt the user to grant or deny image analysis consent!
    boolean askConsent = state.isGeminiConsentRequired();
    
    // 5. Current App
    String packageName = state.getCurrentPackageName();
});
```

### Step 3: Resolving Unknown Apps (User Prompt)
When `state.isCheckRequiredForUnknownApp()` is true, show a dialog to the user. Once the user makes a choice, save it to the DB so the engine knows how to rate it:

```java
// Run this in a background thread/executor!
AppPolicy newPolicy = new AppPolicy(packageName, "UNPRODUCTIVE", 2, 0L, 0); // Note: 0 sets consent to Unknown
AppDatabase.getInstance(context).appPolicyDao().insertPolicy(newPolicy);
```

### Step 4: Resolving Gemini Consent (User Prompt)
When `state.isGeminiConsentRequired()` is true, show a dialog informing the user about data privacy and Gemini AI analysis for this specific app. Save their decision using the helper function:

```java
// 1 = Granted, 2 = Denied. 
// Do NOT pass 0 here (0 means unknown/unanswered).
ProductivityEngine.getInstance().setGeminiConsent(packageName, 1);
```

### Step 5: Configuration
To test the Gemini API, you MUST insert your true API KEY in `app/src/main/java/com/example/screentests/network/GeminiClient.java` (variable `API_KEY`). Avoid hardcoding it into the repository for production!
