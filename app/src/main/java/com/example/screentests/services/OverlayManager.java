package com.example.screentests.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;

import com.example.screentests.R;
import com.example.screentests.chat.QueenBeeChatManager;
import com.example.screentests.chat.QueenBeeUiState;
import com.example.screentests.chat.QueenMood;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.engine.ProductivityState;

public class OverlayManager extends Service {
    //Variables extracted by AI
    private static final String TAG = "OverlayManager";
    private static final String CHANNEL_ID = "OverlayServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private View interventionOverlay;
    private View categorizationOverlay;
    private int lastLevel = -1;
    private boolean isInterventionShowing = false;
    private boolean isCategorizationShowing = false;
    private String lastCategorizationPackage = "";
    private boolean isObserverRegistered = false;
    private BeeManager beeManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final long KICK_BLOCK_MS = 5 * 60 * 1000L;//App block after intervention: 5min

    // How long the Queen's final line + verdict banner stay on screen before the end-game
    // (honey refill reset / app block) actually runs. Long enough to read her reasoning.
    private static final long REFILL_LINGER_MS = 6000L;
    private static final long KICK_LINGER_MS = 4500L;

    // AI-added: lets TrackerAccessibilityService check whether the service already runs instead
    // of firing startForegroundService() on every single window-state change.
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    // Live references to the intervention views while it is showing (null otherwise).
    private TextView chatDisplay;
    private ImageView queenIcon;
    private ImageView screenshotView;
    private TextView screenshotCaptionView;
    private View decisionBanner;
    private TextView decisionBannerText;
    private boolean screenshotShown = false;
    private String activeSessionId;
    private String activePackageName = "";
    // AI-added: the app the user was caught in when the intervention OPENED. The kick verdict
    // must block this app — activePackageName keeps tracking live window changes, so by decision
    // time it could point at the launcher (user pressed home mid-chat) or another app entirely.
    private String interventionPackage = "";
    private QueenBeeUiState.Decision lastDecision = QueenBeeUiState.Decision.NONE;
    // AI-added: token for the delayed end-game runnables (honey refill frames, score reset, app
    // block) so an early teardown can cancel anything still pending — a stale blockApp() firing
    // after the master switch was flipped off used to yank the user to HOME out of nowhere.
    private static final Object END_GAME_TOKEN = new Object();

    //talking anim
    private boolean talkingActive = false;
    private boolean talkingFrameTwo = false;

    private final Observer<ProductivityState> stateObserver = state -> {
        Log.d(TAG, "Received state update: score=" + state.getScore() + ", level=" + state.getLevel() + ", showIntervention=" + state.isShowInterventionOverlay());
        activePackageName = state.getCurrentPackageName();
        updateBees(state.getLevel());
        updateIntervention(state.isShowInterventionOverlay(), state.getQueenBeeSessionId());
        updateCategorizationOverlay(state.isCheckRequiredForUnknownApp(), state.getCurrentPackageName());
    };

    //Backend state observer, partially AI generated
    private final Observer<QueenBeeUiState> queenUiObserver = this::renderQueenUiState;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        running = true;
        // AI-added: this START_STICKY service can be resurrected after process death without
        // MainActivity ever running; make sure the engine (and its persisted enabled/interval
        // settings) is alive before we observe it. init() is idempotent.
        ProductivityEngine.getInstance().init(getApplicationContext());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        beeManager = new BeeManager(this, windowManager, 0);
        createNotificationChannel();
    }

    @Override//AI generated
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bee Productive")
                .setContentText("Tracker is active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        if (!isObserverRegistered) {
            ProductivityEngine.getInstance().getState().observeForever(stateObserver);
            QueenBeeChatManager.getInstance().getUiState().observeForever(queenUiObserver);
            isObserverRegistered = true;
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateBees(int level) {
        if (level == lastLevel) return;
        Log.d(TAG, "Updating bees for level: " + level);

        // AI-changed: initBeeSwarm() starts the simulation thread, so calling it unconditionally
        // meant every drop to level 0 spun up a fresh sim thread that removeAllBees() killed one
        // line later — pointless thread churn on every calm-down / master-switch-off.
        if (level > 0) {
            beeManager.initBeeSwarm(level); // ensures the score-driven simulation is running
        } else {
            beeManager.removeAllBees();
        }

        lastLevel = level;
    }

    private void updateIntervention(boolean show, String sessionId) {
        if (show == isInterventionShowing) return;
        Log.d(TAG, "Updating intervention, show: " + show);

        if (show) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Cannot show intervention: No Overlay Perms");
                return;
            }
            // AI-added: never open the intervention without a live Queen session. Without one
            // the overlay is a dead end — no opening line ever arrives (the Queen just loops
            // her talking animation over the placeholder text), the send button has no target,
            // and there is no decision or timeout that could ever close the window again.
            if (sessionId == null) {
                Log.w(TAG, "Cannot show intervention: no Queen Bee session attached");
                return;
            }

            // Apply Material theme to the service context for proper inflation of Material Components
            ContextThemeWrapper wrapper = new ContextThemeWrapper(this, R.style.Theme_Screentests);
            interventionOverlay = LayoutInflater.from(wrapper).inflate(R.layout.overlay_intervention, null);

            // Layout params for an overlay that needs keyboard input
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);

            // Adjust soft input mode to push content up
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

            // Initialize UI components (kept as fields for the observer).
            // Partially AI generated / Modified by AI
            screenshotView = interventionOverlay.findViewById(R.id.lastActivityScreenshot);
            screenshotCaptionView = interventionOverlay.findViewById(R.id.screenshotCaption);
            decisionBanner = interventionOverlay.findViewById(R.id.decisionBanner);
            decisionBannerText = interventionOverlay.findViewById(R.id.decisionBannerText);
            View chatContainer = interventionOverlay.findViewById(R.id.chatContainer);
            chatDisplay = interventionOverlay.findViewById(R.id.chatMessageDisplay);
            EditText chatInput = interventionOverlay.findViewById(R.id.chatEditText);
            ImageButton sendButton = interventionOverlay.findViewById(R.id.sendButton);
            queenIcon = interventionOverlay.findViewById(R.id.queenBeeIcon);

            activeSessionId = sessionId;
            interventionPackage = activePackageName; // the app the user was caught in
            lastDecision = QueenBeeUiState.Decision.NONE;
            // The screenshot now arrives through the QueenBeeUiState LiveData — the Queen decides
            // when to hold it up (renderQueenUiState), so it starts hidden.
            screenshotShown = false;
            screenshotView.setVisibility(View.GONE);

            //Send = hand text to manager -> back to uiState
            Runnable sendAction = () -> {
                String text = chatInput.getText().toString().trim();
                if (!text.isEmpty() && activeSessionId != null) {
                    chatInput.setText("");
                    QueenBeeChatManager.getInstance().sendMessage(activeSessionId, text, null);
                }
            };

            sendButton.setOnClickListener(v -> sendAction.run());
            chatInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                   (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    sendAction.run();
                    return true;
                }
                return false;
            });

            try {
                windowManager.addView(interventionOverlay, params);
                isInterventionShowing = true;
                animateInterventionIn(interventionOverlay, chatContainer, queenIcon);
                //queen text
                renderQueenUiState(QueenBeeChatManager.getInstance().getUiState().getValue());
            } catch (Exception e) {
                Log.e(TAG, "Error adding intervention overlay", e);
            }
        } else {//hide overlay ERRORS HERE (fixed now but might be back)
            stopTalkingAnimation();
            // AI-added: drop any end-game work that hasn't fired yet. In the normal flow the
            // delayed reset/block already ran (it is what caused this hide); on an early
            // teardown (master switch off, service destroyed) a pending blockApp() must not
            // fire minutes later against whatever the user is doing then.
            mainHandler.removeCallbacksAndMessages(END_GAME_TOKEN);
            chatDisplay = null;
            queenIcon = null;
            screenshotView = null;
            screenshotCaptionView = null;
            decisionBanner = null;
            decisionBannerText = null;
            screenshotShown = false;
            activeSessionId = null;
            interventionPackage = "";
            lastDecision = QueenBeeUiState.Decision.NONE;//does this need to be reset?
            if (interventionOverlay != null) {
                try {
                    windowManager.removeView(interventionOverlay);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing intervention overlay", e);
                }
                interventionOverlay = null;
            }
            isInterventionShowing = false;
        }
    }

    //Values generated by AI
    private void animateInterventionIn(View overlay, View chatContainer, View queen) {
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(250).start();
        if (chatContainer != null) {
            chatContainer.setTranslationY(120f);
            chatContainer.setAlpha(0f);
            chatContainer.animate().translationY(0f).alpha(1f).setDuration(350).setStartDelay(80).start();
        }
        if (queen != null) {
            queen.setTranslationX(220f);
            queen.setTranslationY(220f);
            queen.setAlpha(0f);
            queen.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(400).setStartDelay(120).start();
        }
    }

    //For last line container, mood, screenshot evidence, final descision
    //Partially AI generated / Modified by AI
    private void renderQueenUiState(QueenBeeUiState s) {
        if (s == null || chatDisplay == null || queenIcon == null) return;//no display

        //Text container
        if (s.text != null && !s.text.isEmpty() && !s.text.equals(chatDisplay.getText().toString())) {
            crossfadeText(chatDisplay, s.text);
        }

        //Screenshot evidence: shown the moment the Queen holds it up, then stays for the session.
        if (s.showScreenshot && !screenshotShown && s.screenshotBase64 != null) {
            screenshotShown = true;
            showEvidenceScreenshot(s.screenshotBase64, s.screenshotCaption);
        }

        //The bee itself
        if (s.thinking) {
            stopTalkingAnimation();
            queenIcon.setImageResource(moodToDrawable(QueenMood.THINKING));
        } else if (s.speaker == QueenBeeUiState.Speaker.NONE) {
            // AI-added: idle placeholder state — the session hasn't produced anything yet. Its
            // default mood is TALKING_1, so rendering it like a real reply looped the talking
            // animation forever over the placeholder text ("stuck talking on the first message
            // without ever thinking"). Show the thinking pose until real session state arrives.
            stopTalkingAnimation();
            queenIcon.setImageResource(moodToDrawable(QueenMood.THINKING));
        } else if (s.mood == QueenMood.TALKING_1 || s.mood == QueenMood.TALKING_2) {
            startTalkingAnimation();
        } else {
            stopTalkingAnimation();
            queenIcon.setImageResource(moodToDrawable(s.mood));
        }

        //Final decision
        if (s.decision != QueenBeeUiState.Decision.NONE && lastDecision == QueenBeeUiState.Decision.NONE) {
            lastDecision = s.decision;
            showDecisionBanner(s.decision == QueenBeeUiState.Decision.REFILL);
            if (s.decision == QueenBeeUiState.Decision.REFILL) {
                playHoneyRefillThenReset();
            } else {
                playKickThenBlock();
            }
        }
    }

    /**
     * Decodes the base64 evidence screenshot off the main thread and fades it (plus its caption)
     * into the overlay. AI generated
     */
    private void showEvidenceScreenshot(String base64, String caption) {
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                byte[] decoded = Base64.decode(base64, Base64.DEFAULT);
                bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
            } catch (Exception e) {
                Log.e(TAG, "Failed to decode evidence screenshot", e);
            }
            final Bitmap result = bitmap;
            if (result == null) return;
            mainHandler.post(() -> {
                ImageView shot = screenshotView;
                if (shot == null) return; // overlay closed meanwhile
                shot.setImageBitmap(result);
                shot.setAlpha(0f);
                shot.setVisibility(View.VISIBLE);
                // AI-changed: full opacity — the screenshot no longer sits on top of the chat
                // text (it moved to the bottom of the layout), so it can be fully readable.
                shot.animate().alpha(1f).setDuration(400).start();
                TextView cap = screenshotCaptionView;
                if (cap != null && caption != null && !caption.isEmpty()) {
                    cap.setText("Caught in the act: " + caption);
                    cap.setAlpha(0f);
                    cap.setVisibility(View.VISIBLE);
                    cap.animate().alpha(1f).setDuration(400).start();
                }
            });
        }).start();
    }

    /**
     * Pops the verdict banner in so the user immediately sees that the Queen has decided.
     * AI generated
     */
    private void showDecisionBanner(boolean refill) {
        View banner = decisionBanner;
        TextView text = decisionBannerText;
        if (banner == null || text == null) return;
        text.setText(refill ? "🍯 Honey refilled!" : "🚫 The Queen says: out!");
        if (banner instanceof com.google.android.material.card.MaterialCardView) {
            ((com.google.android.material.card.MaterialCardView) banner).setCardBackgroundColor(
                    getColor(refill ? R.color.decision_refill : R.color.decision_kick));
        }
        banner.setScaleX(0.6f);
        banner.setScaleY(0.6f);
        banner.setAlpha(0f);
        banner.setVisibility(View.VISIBLE);
        banner.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
                .setInterpolator(new android.view.animation.OvershootInterpolator()).start();
    }












    /// //////////////////////////////////////////////////////////////////////////////////  Helper functions
    private void crossfadeText(TextView view, String newText) {
        view.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            view.setText(newText);
            view.animate().alpha(1f).setDuration(160).start();
        }).start();
    }

    private void startTalkingAnimation() {
        if (talkingActive) return;
        talkingActive = true;
        mainHandler.post(talkingRunnable);
    }

    private void stopTalkingAnimation() {
        talkingActive = false;
        mainHandler.removeCallbacks(talkingRunnable);
    }

    private final Runnable talkingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!talkingActive || queenIcon == null) return;
            talkingFrameTwo = !talkingFrameTwo;
            queenIcon.setImageResource(moodToDrawable(talkingFrameTwo ? QueenMood.TALKING_2 : QueenMood.TALKING_1));
            mainHandler.postDelayed(this, 260);
        }
    };

    private int moodToDrawable(QueenMood mood) {
        switch (mood) {
            case SAD: return R.drawable.queen_sad;
            case HAPPY: return R.drawable.queen_happy;
            case TALKING_2: return R.drawable.queen_talking_2;
            case SHOWING_HONEY: return R.drawable.queen_showing_honey;
            case EXCLAIMING: return R.drawable.queen_exclaiming;
            case ASKING: return R.drawable.queen_asking;
            case THINKING: return R.drawable.queen_asking;
            case TALKING_1:
            default: return R.drawable.queen_talking_1;
        }
    }

    /**
     * Refill verdict: the Queen presents the honey, the pot fills up, and her final line stays
     * readable for {@link #REFILL_LINGER_MS} before the score resets and the overlay closes.
     * Partially AI generated / Modified by AI
     */
    private void playHoneyRefillThenReset() {
        stopTalkingAnimation();
        if (queenIcon != null) queenIcon.setImageResource(moodToDrawable(QueenMood.SHOWING_HONEY));
        final int[] frames = {R.drawable.honey_fill_1, R.drawable.honey_fill_2, R.drawable.honey_fill_3};
        for (int i = 0; i < frames.length; i++) {
            final int res = frames[i];
            mainHandler.postDelayed(() -> {
                if (queenIcon != null) queenIcon.setImageResource(res);
            }, END_GAME_TOKEN, 900L + i * 500L);
        }
        mainHandler.postDelayed(
                () -> ProductivityEngine.getInstance().resetScore(),
                END_GAME_TOKEN, REFILL_LINGER_MS);
    }

    /**
     * Kick verdict: show the Queen's outrage and her final line for {@link #KICK_LINGER_MS},
     * then block the app + send the user home. Partially AI generated / Modified by AI
     */
    private void playKickThenBlock() {
        stopTalkingAnimation();
        if (queenIcon != null) queenIcon.setImageResource(moodToDrawable(QueenMood.EXCLAIMING));
        // AI-changed: block the app the intervention was opened for, NOT the live current
        // package — by decision time that could already be the launcher (user pressed home
        // mid-chat), and blocking the launcher stunlocked the homescreen in a HOME loop.
        final String pkg = interventionPackage;
        mainHandler.postDelayed(
                () -> ProductivityEngine.getInstance().blockApp(pkg, KICK_BLOCK_MS),
                END_GAME_TOKEN, KICK_LINGER_MS);
    }


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Overlay Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        super.onDestroy();
        running = false;
        if (isObserverRegistered) {
            ProductivityEngine.getInstance().getState().removeObserver(stateObserver);
            QueenBeeChatManager.getInstance().getUiState().removeObserver(queenUiObserver);
            isObserverRegistered = false;
        }
        stopTalkingAnimation();
        // AI-added: tear down every window this service put on screen. Stopping the service
        // (e.g. via the master switch) without process death used to leave bee views, the swipe
        // layer and any open overlay orphaned on screen with nobody left to remove them.
        beeManager.removeAllBees();
        updateIntervention(false, null);
        updateCategorizationOverlay(false, "");
    }





    /// //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////// end of helper functions
    private void updateCategorizationOverlay(boolean show, String packageName) {
        if (show == isCategorizationShowing && packageName.equals(lastCategorizationPackage)) return;
        Log.d(TAG, "Update categorization overlay: show=" + show + ", pkg=" + packageName);

        // Remove old if exists
        if (categorizationOverlay != null) {
            try {
                windowManager.removeView(categorizationOverlay);
            } catch (Exception e) {
                Log.e(TAG, "Error removing categorization overlay", e);
            }
            categorizationOverlay = null;
        }

        if (show) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Cannot show categorization overlay: Overlay permission not granted");
                return;
            }

            ContextThemeWrapper wrapper = new ContextThemeWrapper(this, R.style.Theme_Screentests);
            categorizationOverlay = LayoutInflater.from(wrapper).inflate(R.layout.dialog_app_categorization, null);
            
            // Background to dim the underlying app
            categorizationOverlay.setBackgroundColor(0xAA000000);

            TextView packageLabel = categorizationOverlay.findViewById(R.id.packageNameLabel);
            packageLabel.setText("Package: " + packageName);

            RadioGroup statusGroup = categorizationOverlay.findViewById(R.id.statusRadioGroup);
            SeekBar severitySeekBar = categorizationOverlay.findViewById(R.id.severitySeekBar);
            Button saveButton = categorizationOverlay.findViewById(R.id.saveButton);

            saveButton.setOnClickListener(v -> {
                String status = statusGroup.getCheckedRadioButtonId() == R.id.radioProductive ? "PRODUCTIVE" : "UNPRODUCTIVE";
                int severity = severitySeekBar.getProgress() + 1;
                ProductivityEngine.getInstance().updateAppPolicy(packageName, status, severity);
            });

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);

            try {
                windowManager.addView(categorizationOverlay, params);
                isCategorizationShowing = true;
                lastCategorizationPackage = packageName;
            } catch (Exception e) {
                Log.e(TAG, "Error adding categorization overlay", e);
            }
        } else {
            isCategorizationShowing = false;
            lastCategorizationPackage = "";
        }
    }
}
