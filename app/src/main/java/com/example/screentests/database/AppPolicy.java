package com.example.screentests.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_policies")
public class AppPolicy {
    
    @PrimaryKey
    @NonNull
    public String packageName;
    
    public String status; // Evaluated status: "PRODUCTIVE", "UNPRODUCTIVE", "UNKNOWN", "BLOCKED"
    
    // Severity of unproductivity (1 = low, 2 = medium, 3 = high)
    public int severity;

    public long blockedUntilMillis;

    // 0 = Unknown/Needs Ask, 1 = Granted, 2 = Denied
    public int geminiConsent = 0;

    public AppPolicy(@NonNull String packageName, String status, int severity, long blockedUntilMillis, int geminiConsent) {
        this.packageName = packageName;
        this.status = status;
        this.severity = severity;
        this.blockedUntilMillis = blockedUntilMillis;
        this.geminiConsent = geminiConsent;
    }
}