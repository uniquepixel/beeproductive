package com.example.screentests.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "activity_logs")
public class ActivityLog {
    
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public long timestamp;
    
    public String packageName;
    
    public String geminiSummary;

    public ActivityLog(long timestamp, String packageName, String geminiSummary) {
        this.timestamp = timestamp;
        this.packageName = packageName;
        this.geminiSummary = geminiSummary;
    }
}