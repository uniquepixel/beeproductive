package com.example.screentests.engine;

import android.content.Context;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.screentests.database.AppDatabase;
import com.example.screentests.database.AppPolicy;
import com.example.screentests.database.AppStatus;
import com.example.screentests.database.ActivityLog;
import com.example.screentests.network.GeminiClient;
import com.example.screentests.services.TrackerAccessibilityService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProductivityEngine {
    private static final String TAG = "ProductivityEngine";
    private static ProductivityEngine instance;
    private final MutableLiveData<ProductivityState> stateLiveData = new MutableLiveData<>(ProductivityState.initial());
    
    // Internal thread for the scheduled tick
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // Executor for DB calls
    private final ScheduledExecutorService dbExecutor = Executors.newSingleThreadScheduledExecutor();
    
    private Context applicationContext;
    private int currentScore = 0;
    private int minutesUnproductiveInCurrentSession = 0;
    
    private static final int SCORE_MAX = 100;
    private static final int SCORE_MIN = 0;
    private static final int SCREENSHOT_INTERVAL_MINUTES = 2;

    private ProductivityEngine() {
        // Run the logic tick every minute
        scheduler.scheduleAtFixedRate(this::tickScoreLogic, 1, 1, TimeUnit.MINUTES);
    }

    public static synchronized ProductivityEngine getInstance() {
        if (instance == null) {
            instance = new ProductivityEngine();
        }
        return instance;
    }

    public void init(Context context) {
        this.applicationContext = context.getApplicationContext();
    }

    // Frontend can observe this LiveData to get UI updates
    public LiveData<ProductivityState> getState() {
        return stateLiveData;
    }

    /**
     * Called by AccessibilityService when a new app is opened.
     */
    public void onAppChanged(String packageName) {
        if (applicationContext == null) return;
        
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(applicationContext);
            AppPolicy policy = db.appPolicyDao().getPolicyForPackage(packageName);
            
            boolean isUnknown = false;
            
            if (policy == null) {
                policy = new AppPolicy(packageName, AppStatus.UNKNOWN.name(), 1, 0, 0);
                db.appPolicyDao().insertPolicy(policy);
                isUnknown = true;
            } else if (AppStatus.UNKNOWN.name().equals(policy.status)) {
                isUnknown = true;
            } else if (AppStatus.BLOCKED.name().equals(policy.status) && System.currentTimeMillis() < policy.blockedUntilMillis) {
                // If it is blocked via Gemini penalty, kick them out immediately
                TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
                if (tracker != null) {
                    tracker.enforceLockout();
                }
            }

            boolean needsGeminiConsent = (policy.geminiConsent == 0);
            postStateUpdate(packageName, isUnknown, needsGeminiConsent);
        });
    }

    /**
     * The internal background ticker. Runs every 1 minute.
     */
    private void tickScoreLogic() {
        if (applicationContext == null) return;
        
        ProductivityState currentState = stateLiveData.getValue();
        if (currentState == null || currentState.getCurrentPackageName().isEmpty()) return;
        
        String packageName = currentState.getCurrentPackageName();
        
        AppDatabase db = AppDatabase.getInstance(applicationContext);
        AppPolicy policy = db.appPolicyDao().getPolicyForPackage(packageName);
        
        if (policy == null || AppStatus.UNKNOWN.name().equals(policy.status)) {
            minutesUnproductiveInCurrentSession = 0;
            return;
        }
        
        if (AppStatus.BLOCKED.name().equals(policy.status) && System.currentTimeMillis() < policy.blockedUntilMillis) {
             TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
             if (tracker != null) tracker.enforceLockout();
             return;
        }

        if (AppStatus.UNPRODUCTIVE.name().equals(policy.status)) {
            // Apply severity penalty
            int penalty = calculatePenalty(policy.severity);
            currentScore = Math.min(SCORE_MAX, currentScore + penalty);
            minutesUnproductiveInCurrentSession++;
            
            // Check if we should take a screenshot to track activity for Gemini
            if (minutesUnproductiveInCurrentSession % SCREENSHOT_INTERVAL_MINUTES == 0) {
                if (policy.geminiConsent == 1) {
                    triggerBackgroundScreenshotAndAnalysis(packageName);
                }
            }
            
        } else if (AppStatus.PRODUCTIVE.name().equals(policy.status)) {
            // Cool down effect
            currentScore = Math.max(SCORE_MIN, currentScore - 15);
            minutesUnproductiveInCurrentSession = 0;
        }

        boolean needsGeminiConsent = (policy != null && policy.geminiConsent == 0);
        // Post immediately to UI
        postStateUpdate(packageName, false, needsGeminiConsent);
    }
    
    private void triggerBackgroundScreenshotAndAnalysis(String packageName) {
        TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
        if (tracker != null) {
            tracker.takeScreenshotBase64(new TrackerAccessibilityService.ScreenshotCallback() {
                @Override
                public void onSuccess(String base64Image) {
                    GeminiClient.getInstance().analyzeScreenshot(base64Image, new GeminiClient.GeminiCallback() {
                        @Override
                        public void onSuccess(String textResponse) {
                            dbExecutor.execute(() -> {
                                ActivityLog log = new ActivityLog(System.currentTimeMillis(), packageName, textResponse);
                                AppDatabase.getInstance(applicationContext).activityLogDao().insertLog(log);
                            });
                        }
                        
                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Gemini failed: " + error);
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    Log.e(TAG, "Screenshot failed: " + error);
                }
            });
        }
    }
    
    private int calculatePenalty(int severity) {
        switch (severity) {
            case 3: return 20;
            case 2: return 10;
            case 1: default: return 5;
        }
    }

    private void postStateUpdate(String packageName, boolean isUnknown, boolean needsGeminiConsent) {
        int level = computeLevel(currentScore);
        boolean showOverlay = currentScore >= SCORE_MAX;
        
        ProductivityState newState = new ProductivityState(
            currentScore, 
            level, 
            showOverlay, 
            packageName, 
            isUnknown,
            needsGeminiConsent
        );
        stateLiveData.postValue(newState);
    }

    private int computeLevel(int score) {
        if (score <= 20) return 0;
        if (score <= 50) return 1;
        if (score <= 75) return 2;
        if (score <= 99) return 3;
        return 4; // 100+
    }

    public void resetScore() {
        currentScore = 0;
        ProductivityState state = stateLiveData.getValue();
        if (state != null) {
            postStateUpdate(state.getCurrentPackageName(), state.isCheckRequiredForUnknownApp(), state.isGeminiConsentRequired());
        }
    }

    public void setGeminiConsent(String packageName, int consent) {
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(applicationContext);
            AppPolicy policy = db.appPolicyDao().getPolicyForPackage(packageName);
            if (policy != null) {
                policy.geminiConsent = consent;
                db.appPolicyDao().updatePolicy(policy);
                
                // If it is the current app, update state
                ProductivityState state = stateLiveData.getValue();
                if (state != null && packageName.equals(state.getCurrentPackageName())) {
                    postStateUpdate(packageName, state.isCheckRequiredForUnknownApp(), consent == 0);
                }
            }
        });
    }
}