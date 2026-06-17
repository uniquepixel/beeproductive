package com.example.screentests.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface AppPolicyDao {

    @Query("SELECT * FROM app_policies WHERE packageName = :packageName LIMIT 1")
    AppPolicy getPolicyForPackage(String packageName);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPolicy(AppPolicy appPolicy);

    @Update
    void updatePolicy(AppPolicy appPolicy);

    @Query("DELETE FROM app_policies")
    void deleteAllPolicies();
}
