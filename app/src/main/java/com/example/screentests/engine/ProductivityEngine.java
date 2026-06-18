package com.example.screentests.engine;

import android.content.Context;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.screentests.database.AppDatabase;
import com.example.screentests.database.AppPolicy;
import com.example.screentests.database.AppStatus;
import com.example.screentests.database.ActivityLog;
import com.example.screentests.network.OpenRouterClient;
import com.example.screentests.chat.ChatSession;
import com.example.screentests.chat.QueenBeeChatManager;
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
    // Id of the Queen Bee chat session created when the score hits the max.
    // Stays set until resetScore() so we don't spawn a new session every tick.
    private String activeQueenSessionId = null;
    
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
        QueenBeeChatManager.getInstance().init(this.applicationContext);
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

        // Exception, wann nichts überprüft werden soll
        if (packageName.contains("netlauncher") || packageName.contains("launcher") || packageName.equals("com.android.systemui")) {
            postStateUpdate(packageName, false, false);
            return;
        }
        
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
                // If it is blocked via AI penalty, kick them out immediately
                TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
                if (tracker != null) {
                    tracker.enforceLockout();
                }
            }

            boolean needsAiConsent = (policy.aiConsent == 0);
            postStateUpdate(packageName, isUnknown, needsAiConsent);
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
            
            // Check if we should take a screenshot to track activity for the AI
            if (minutesUnproductiveInCurrentSession % SCREENSHOT_INTERVAL_MINUTES == 0) {
                if (policy.aiConsent == 1) {
                    triggerBackgroundScreenshotAndAnalysis(packageName);
                }
            }

        } else if (AppStatus.PRODUCTIVE.name().equals(policy.status)) {
            // Cool down effect
            currentScore = Math.max(SCORE_MIN, currentScore - 15);
            minutesUnproductiveInCurrentSession = 0;
        }

        boolean needsAiConsent = (policy != null && policy.aiConsent == 0);
        // Post immediately to UI
        postStateUpdate(packageName, false, needsAiConsent);
    }
    
    private void triggerBackgroundScreenshotAndAnalysis(String packageName) {
        TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
        if (tracker != null) {
            tracker.takeScreenshotBase64(new TrackerAccessibilityService.ScreenshotCallback() {
                @Override
                public void onSuccess(String base64Image) {
                    OpenRouterClient.getInstance().analyzeScreenshot(base64Image, new OpenRouterClient.Callback() {
                        @Override
                        public void onSuccess(String textResponse) {
                            dbExecutor.execute(() -> {
                                // Persist both the AI summary and the screenshot itself.
                                ActivityLog log = new ActivityLog(System.currentTimeMillis(), packageName, textResponse, base64Image);
                                AppDatabase.getInstance(applicationContext).activityLogDao().insertLog(log);
                            });
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "OpenRouter analysis failed: " + error);
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

    private void postStateUpdate(String packageName, boolean isUnknown, boolean needsAiConsent) {
        int level = computeLevel(currentScore);
        boolean atMax = currentScore >= SCORE_MAX;

        // When the score hits the max, fire up the Queen Bee chat exactly once.
        // The session stays alive until resetScore() so we don't respawn every tick.
        if (atMax && activeQueenSessionId == null) {
            ChatSession session = QueenBeeChatManager.getInstance().startSession(currentScore, null);
            activeQueenSessionId = session.sessionId;
            Log.d(TAG, "Queen Bee session started: " + activeQueenSessionId);
        }

        ProductivityState newState = new ProductivityState(
            currentScore,
            level,
            atMax,              // showInterventionOverlay (existing behaviour)
            packageName,
            isUnknown,
            needsAiConsent,
            atMax,              // showQueenBeeChat
            activeQueenSessionId
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
        // End the Queen Bee chat that was opened at max score, if any.
        if (activeQueenSessionId != null) {
            QueenBeeChatManager.getInstance().endSession(activeQueenSessionId);
            activeQueenSessionId = null;
        }
        ProductivityState state = stateLiveData.getValue();
        if (state != null) {
            postStateUpdate(state.getCurrentPackageName(), state.isCheckRequiredForUnknownApp(), state.isAiConsentRequired());
        }
    }

    public void resetAllCategorizations() {
        dbExecutor.execute(() -> {
            AppDatabase.getInstance(applicationContext).appPolicyDao().deleteAllPolicies();

            // After clearing, we might want to re-check the current app
            ProductivityState state = stateLiveData.getValue();
            if (state != null && !state.getCurrentPackageName().isEmpty()) {
                onAppChanged(state.getCurrentPackageName());
            }
        });
    }

    /**
     * For debugging/testing the UI without modifying the internal score.
     */
    public void debugTriggerUI(int level, boolean showIntervention) {
        ProductivityState current = stateLiveData.getValue();
        String pkg = (current != null) ? current.getCurrentPackageName() : "";
        ProductivityState debugState = new ProductivityState(
                currentScore,
                level,
                showIntervention,
                pkg,
                false,
                false,
                showIntervention,
                activeQueenSessionId
        );
        stateLiveData.postValue(debugState);
    }

    public void setAiConsent(String packageName, int consent) {
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(applicationContext);
            AppPolicy policy = db.appPolicyDao().getPolicyForPackage(packageName);
            if (policy != null) {
                policy.aiConsent = consent;
                db.appPolicyDao().updatePolicy(policy);
                
                // If it is the current app, update state
                ProductivityState state = stateLiveData.getValue();
                if (state != null && packageName.equals(state.getCurrentPackageName())) {
                    postStateUpdate(packageName, state.isCheckRequiredForUnknownApp(), consent == 0);
                }
            }
        });
    }

    public void updateAppPolicy(String packageName, String status, int severity) {
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(applicationContext);
            AppPolicy newPolicy = new AppPolicy(packageName, status, severity, 0L, 0);
            db.appPolicyDao().insertPolicy(newPolicy);

            // Refresh state immediately
            onAppChanged(packageName);
        });
    }
}