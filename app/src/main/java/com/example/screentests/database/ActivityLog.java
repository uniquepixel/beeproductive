package com.example.screentests.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "activity_logs")
public class ActivityLog {

    @PrimaryKey(autoGenerate = true)
    public int id;

    public long timestamp;

    public String packageName;

    /** One-sentence summary of the screen, produced by the AI vision model. */
    public String aiSummary;

    /** The captured screenshot as a base64-encoded JPEG. */
    public String screenshotBase64;

    public ActivityLog(long timestamp, String packageName, String aiSummary, String screenshotBase64) {
        this.timestamp = timestamp;
        this.packageName = packageName;
        this.aiSummary = aiSummary;
        this.screenshotBase64 = screenshotBase64;
    }
}
