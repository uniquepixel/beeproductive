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
import java.util.concurrent.ScheduledFuture;
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
    // AI-changed: score state is mutated from three different threads (the tick scheduler,
    // the dbExecutor via onAppChanged/setAiConsent, and the main thread via resetScore/
    // setEnabled). Without a lock the reads in postStateUpdate() could see torn state — e.g.
    // two threads both observing "atMax && activeQueenSessionId == null" and spawning two
    // Queen Bee sessions. All access to the three fields below goes through scoreLock now.
    private final Object scoreLock = new Object();
    private int currentScore = 0;
    private int minutesUnproductiveInCurrentSession = 0;
    // Id of the Queen Bee chat session created when the score hits the max.
    // Stays set until resetScore() so we don't spawn a new session every tick.
    private String activeQueenSessionId = null;
    
    private static final int SCORE_MAX = 100;
    private static final int SCORE_MIN = 0;
    private static final int SCREENSHOT_INTERVAL_MINUTES = 2;

    // Configurable interval between score ticks. Persisted so a demo slider survives restarts.
    private static final String PREFS_NAME = "beeproductive_prefs";
    private static final String KEY_SCORE_INTERVAL_MS = "score_interval_ms";
    private static final long DEFAULT_SCORE_INTERVAL_MS = 60_000L; // 1 minute
    private static final long MIN_SCORE_INTERVAL_MS = 1_000L;      // floor to avoid pathological values

    private static final String KEY_MAX_BEES = "max_bees";
    private static final int DEFAULT_MAX_BEES = 24;

    private static final String KEY_ENABLED = "engine_enabled";

    private volatile long scoreIntervalMillis = DEFAULT_SCORE_INTERVAL_MS;
    private volatile int maxBees = DEFAULT_MAX_BEES;
    private volatile boolean enabled = true;
    private ScheduledFuture<?> tickFuture;

    private ProductivityEngine() {
        // Arm the score ticker at the default interval. init() re-arms it once the
        // persisted interval is available.
        scheduleTick();
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

        // Restore the persisted score interval and re-arm the ticker at that rate.
        scoreIntervalMillis = applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_SCORE_INTERVAL_MS, DEFAULT_SCORE_INTERVAL_MS);
        
        maxBees = applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MAX_BEES, DEFAULT_MAX_BEES);

        enabled = applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);

        scheduleTick();
    }

    /**
     * (Re)schedules the background score ticker at the current {@link #scoreIntervalMillis}.
     * Safe to call repeatedly — any existing schedule is cancelled first.
     */
    private synchronized void scheduleTick() {
        if (tickFuture != null) {
            tickFuture.cancel(false); // don't interrupt an in-flight tick
            tickFuture = null;
        }
        // AI-changed: while the master switch is off there is nothing to tick — previously the
        // scheduler kept waking up every interval (down to 1s) just to bail out in
        // tickScoreLogic(), wasting cycles/battery. setEnabled() re-arms us.
        if (!enabled) return;
        tickFuture = scheduler.scheduleAtFixedRate(
                this::tickScoreLogic, scoreIntervalMillis, scoreIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Current interval (in milliseconds) between score ticks. Default is 60000 (1 minute).
     */
    public long getScoreIntervalMillis() {
        return scoreIntervalMillis;
    }

    /**
     * Sets the interval between score ticks. A shorter interval makes the score build up faster
     * (handy for demos). Values are clamped to a minimum of {@value #MIN_SCORE_INTERVAL_MS} ms,
     * persisted across restarts, and applied immediately.
     */
    public void setScoreIntervalMillis(long millis) {
        scoreIntervalMillis = Math.max(MIN_SCORE_INTERVAL_MS, millis);
        if (applicationContext != null) {
            applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_SCORE_INTERVAL_MS, scoreIntervalMillis)
                    .apply();
        }
        scheduleTick();
    }

    /** Whether the whole app functionality (tracking, swarm, interventions, overlays) is on. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Master on/off switch. When turned off, all activity stops: the score ticker and app
     * tracking are gated off, the running score is cleared, and a neutral state is posted so
     * the OverlayManager removes the bee swarm and any overlays.
     */
    public void setEnabled(boolean value) {
        enabled = value;
        if (applicationContext != null) {
            applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ENABLED, value)
                    .apply();
        }
        if (!value) {
            synchronized (scoreLock) { // AI-changed: don't race an in-flight tick mutating the score
                currentScore = 0;
                minutesUnproductiveInCurrentSession = 0;
                if (activeQueenSessionId != null) {
                    QueenBeeChatManager.getInstance().endSession(activeQueenSessionId);
                    activeQueenSessionId = null;
                }
            }
            // AI-changed: post the neutral state with an EMPTY package name. Previously the old
            // package was kept, so after re-enabling, the ticker resumed scoring the app the user
            // was in when they switched off — even if they had long since moved on (onAppChanged
            // is gated while disabled, so the state could not correct itself until the next app
            // switch). With an empty package the ticker stays inert until a real app change.
            stateLiveData.postValue(new ProductivityState(0, 0, false, "", false, false, false, null));
        }
        // AI-changed: arm or cancel the score ticker to match the switch (see scheduleTick()).
        scheduleTick();
    }

    public int getMaxBees() {
        return maxBees;
    }

    public void setMaxBees(int count) {
        this.maxBees = Math.max(1, count);
        if (applicationContext != null) {
            applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_MAX_BEES, this.maxBees)
                    .apply();
        }
    }

    // Frontend can observe this LiveData to get UI updates
    public LiveData<ProductivityState> getState() {
        return stateLiveData;
    }

    /**
     * Called by AccessibilityService when a new app is opened.
     */
    public void onAppChanged(String packageName) {
        if (applicationContext == null || !enabled) return;

        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(applicationContext);
            AppPolicy policy = db.appPolicyDao().getPolicyForPackage(packageName);

            if (policy == null) {
                // Unseen package. System non-apps (launcher, keyboard, SystemUI) are auto-classified
                // as NEUTRAL so they're consistent and visible in the DB; everything else starts UNKNOWN.
                String initialStatus = isSystemNonApp(packageName)
                        ? AppStatus.NEUTRAL.name() : AppStatus.UNKNOWN.name();
                policy = new AppPolicy(packageName, initialStatus, 1, 0, 0);
                db.appPolicyDao().insertPolicy(policy);
            } else if (AppStatus.BLOCKED.name().equals(policy.status)) {
                if (System.currentTimeMillis() < policy.blockedUntilMillis) {
                    // If it is blocked via AI penalty, kick them out immediately
                    TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
                    if (tracker != null) {
                        tracker.enforceLockout();
                    }
                } else {
                    // AI-changed: an expired block used to leave the app in BLOCKED forever, which
                    // silently exempted it from scoring (neither UNPRODUCTIVE nor PRODUCTIVE branch
                    // matched in the tick). Restore UNPRODUCTIVE — only unproductive apps ever get
                    // blocked — keeping severity and AI consent.
                    policy.status = AppStatus.UNPRODUCTIVE.name();
                    policy.blockedUntilMillis = 0;
                    db.appPolicyDao().updatePolicy(policy);
                }
            }

            // AI-changed: switching to a different app starts a fresh usage session, so the
            // screenshot-cadence counter restarts with it. Previously minutes carried over
            // between unproductive apps, firing a screenshot right after an app switch.
            ProductivityState prev = stateLiveData.getValue();
            if (prev == null || !packageName.equals(prev.getCurrentPackageName())) {
                synchronized (scoreLock) {
                    minutesUnproductiveInCurrentSession = 0;
                }
            }

            boolean isUnknown = AppStatus.UNKNOWN.name().equals(policy.status);
            boolean isNeutral = AppStatus.NEUTRAL.name().equals(policy.status);
            // Neutral apps are "not an app" — never prompt for categorization or AI consent.
            boolean needsAiConsent = !isNeutral && policy.aiConsent == 0;
            postStateUpdate(packageName, isUnknown, needsAiConsent);
        });
    }

    /**
     * Packages that aren't really "apps" the user chooses to use — launchers, the keyboard,
     * the system UI. These are auto-classified as {@link AppStatus#NEUTRAL}.
     */
    private boolean isSystemNonApp(String packageName) {
        return packageName.contains("netlauncher")
                || packageName.contains("launcher")
                || packageName.equals("com.android.systemui");
    }

    /**
     * The internal background ticker. Runs every 1 minute.
     */
    private void tickScoreLogic() {
        // AI-changed: scheduleAtFixedRate() permanently cancels the periodic task if one run
        // throws. A single unexpected exception (e.g. a transient Room/DB error) used to kill
        // the score ticker silently until the next app restart — the score just froze.
        try {
            tickScoreLogicInternal();
        } catch (Exception e) {
            Log.e(TAG, "Score tick failed", e);
        }
    }

    private void tickScoreLogicInternal() {
        if (applicationContext == null || !enabled) return;

        ProductivityState currentState = stateLiveData.getValue();
        if (currentState == null || currentState.getCurrentPackageName().isEmpty()) return;

        String packageName = currentState.getCurrentPackageName();

        AppDatabase db = AppDatabase.getInstance(applicationContext);
        AppPolicy policy = db.appPolicyDao().getPolicyForPackage(packageName);

        if (policy == null
                || AppStatus.UNKNOWN.name().equals(policy.status)
                || AppStatus.NEUTRAL.name().equals(policy.status)) {
            // Unknown apps await categorization; neutral apps are "not an app".
            // Neither moves the score or takes screenshots.
            synchronized (scoreLock) {
                minutesUnproductiveInCurrentSession = 0;
            }
            return;
        }

        if (AppStatus.BLOCKED.name().equals(policy.status) && System.currentTimeMillis() < policy.blockedUntilMillis) {
             TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
             if (tracker != null) tracker.enforceLockout();
             return;
        }

        // AI-changed: score mutation happens under scoreLock so it can't race resetScore()/
        // setEnabled() from other threads; the screenshot trigger is computed inside but fired
        // outside the lock (it fans out to the accessibility service + network).
        boolean takeScreenshot = false;
        synchronized (scoreLock) {
            if (AppStatus.UNPRODUCTIVE.name().equals(policy.status)) {
                // Apply severity penalty
                int penalty = calculatePenalty(policy.severity);
                currentScore = Math.min(SCORE_MAX, currentScore + penalty);
                minutesUnproductiveInCurrentSession++;

                // Check if we should take a screenshot to track activity for the AI
                takeScreenshot = (minutesUnproductiveInCurrentSession % SCREENSHOT_INTERVAL_MINUTES == 0)
                        && policy.aiConsent == 1;
            } else if (AppStatus.PRODUCTIVE.name().equals(policy.status)) {
                // Cool down effect
                currentScore = Math.max(SCORE_MIN, currentScore - 15);
                minutesUnproductiveInCurrentSession = 0;
            }
        }
        if (takeScreenshot) {
            triggerBackgroundScreenshotAndAnalysis(packageName);
        }

        boolean needsAiConsent = (policy.aiConsent == 0);
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
        // AI-changed: a DB job queued before the master switch was flipped off (onAppChanged,
        // setAiConsent, ...) could finish after setEnabled(false) and re-post a live state on
        // top of the cleared one — bees came back while the app was "off". While disabled,
        // only setEnabled()'s neutral state may go out.
        if (!enabled) return;

        // AI-changed: synchronized so score + session id are read/created atomically. This is
        // called from both the tick scheduler and the dbExecutor; unsynchronized, both could
        // see "atMax && no session" and start two Queen Bee sessions.
        synchronized (scoreLock) {
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
    }

    private int computeLevel(int score) {
        if (score <= 20) return 0;
        if (score <= 50) return 1;
        if (score <= 75) return 2;
        if (score <= 99) return 3;
        return 4; // 100+
    }

    public void resetScore() {
        // AI-changed: locked — this runs on the main thread (honey-refill animation) while the
        // tick thread may be mid-mutation; unsynchronized, a concurrent tick could resurrect
        // the score/session right after the reset.
        synchronized (scoreLock) {
            currentScore = 0;
            minutesUnproductiveInCurrentSession = 0;
            // End the Queen Bee chat that was opened at max score, if any.
            if (activeQueenSessionId != null) {
                QueenBeeChatManager.getInstance().endSession(activeQueenSessionId);
                activeQueenSessionId = null;
            }
        }
        ProductivityState state = stateLiveData.getValue();
        if (state != null) {
            postStateUpdate(state.getCurrentPackageName(), state.isCheckRequiredForUnknownApp(), state.isAiConsentRequired());
        }
    }

    /**
     * Kicks the user out of an app when the Queen Bee decides against them: marks the app
     * {@link AppStatus#BLOCKED} for {@code durationMs} (so re-entry is bounced by the existing
     * BLOCKED check in {@link #onAppChanged}), sends the user HOME immediately, and resets the
     * score so the Queen Bee chat ends. Reuses the existing lockout machinery — no new wiring.
     */
    public void blockApp(String packageName, long durationMs) {
        // AI-changed: guard against use before init() — the lambda below dereferenced
        // applicationContext and would have crashed the dbExecutor thread.
        if (packageName == null || packageName.isEmpty() || applicationContext == null) {
            resetScore();
            return;
        }
        final long until = System.currentTimeMillis() + Math.max(0, durationMs);
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(applicationContext);
            AppPolicy existing = db.appPolicyDao().getPolicyForPackage(packageName);
            int severity = existing != null ? existing.severity : 1;
            int aiConsent = existing != null ? existing.aiConsent : 0;
            // Preserve severity/consent; only the status + block window change.
            AppPolicy blocked = new AppPolicy(packageName, AppStatus.BLOCKED.name(), severity, until, aiConsent);
            db.appPolicyDao().insertPolicy(blocked);
        });

        // Send them out of the app right now.
        TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
        if (tracker != null) {
            tracker.enforceLockout();
        }
        // Clear the score so the intervention overlay + Queen Bee chat close.
        resetScore();
    }

    public void resetAllCategorizations() {
        if (applicationContext == null) return; // AI-changed: guard against use before init()
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
        if (applicationContext == null) return; // AI-changed: guard against use before init()
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
        if (applicationContext == null) return; // AI-changed: guard against use before init()
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(applicationContext);
            AppPolicy newPolicy = new AppPolicy(packageName, status, severity, 0L, 0);
            db.appPolicyDao().insertPolicy(newPolicy);

            // Refresh state immediately
            onAppChanged(packageName);
        });
    }

    /**
     * Marks an app as "not an app" ({@link AppStatus#NEUTRAL}) — it will never affect the score
     * and will never prompt for categorization or AI consent.
     */
    public void setAppNeutral(String packageName) {
        updateAppPolicy(packageName, AppStatus.NEUTRAL.name(), 0);
    }
}