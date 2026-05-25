package com.example.screentests.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ActivityLogDao {

    @Insert
    void insertLog(ActivityLog log);

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT :limit")
    List<ActivityLog> getRecentLogs(int limit);

    @Query("SELECT * FROM activity_logs WHERE timestamp >= :sinceMillis ORDER BY timestamp DESC")
    List<ActivityLog> getLogsSince(long sinceMillis);
}
