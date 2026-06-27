package com.example.screentests.database;

public enum AppStatus {
    PRODUCTIVE,
    UNPRODUCTIVE,
    UNKNOWN,
    BLOCKED,
    // "Not an app" — neither productive nor unproductive. Never affects the score
    // and never prompts the user to categorize it (e.g. keyboards, launchers, SystemUI).
    NEUTRAL
}