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
import com.example.screentests.database.ActivityLog;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.engine.ProductivityState;

public class OverlayService extends Service {
    private static final String TAG = "OverlayService";
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

    // How long a kicked-out app stays BLOCKED before it can be reopened.
    private static final long KICK_BLOCK_MS = 5 * 60 * 1000L;

    // Live references to the intervention views while it is showing (null otherwise).
    private TextView chatDisplay;
    private ImageView queenIcon;
    private String activeSessionId;
    private String activePackageName = "";
    private QueenBeeUiState.Decision lastDecision = QueenBeeUiState.Decision.NONE;

    // Two-frame "talking" animation state.
    private boolean talkingActive = false;
    private boolean talkingFrameTwo = false;

    private final Observer<ProductivityState> stateObserver = state -> {
        Log.d(TAG, "Received state update: score=" + state.getScore() + ", level=" + state.getLevel() + ", showIntervention=" + state.isShowInterventionOverlay());
        activePackageName = state.getCurrentPackageName();
        updateBees(state.getLevel());
        updateIntervention(state.isShowInterventionOverlay(), state.getQueenBeeSessionId());
        updateCategorizationOverlay(state.isCheckRequiredForUnknownApp(), state.getCurrentPackageName());
    };

    // Queen Bee chat UI channel — same LiveData+observeForever pattern as the score state.
    private final Observer<QueenBeeUiState> queenUiObserver = this::renderQueenUiState;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        beeManager = new BeeManager(this, windowManager, 0);
        createNotificationChannel();
    }

    @Override
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
        
        beeManager.initBeeSwarm(level);
        if (level > 0) {
            beeManager.startSimulation();
        } else {
            beeManager.removeAllBees();
        }
        
        lastLevel = level;
    }

    private void updateIntervention(boolean show, String sessionId) {
        if (show == isInterventionShowing) return;
        Log.d(TAG, "Updating intervention show: " + show);

        if (show) {
            // Apply Material theme to the service context for proper inflation of Material Components
            ContextThemeWrapper wrapper = new ContextThemeWrapper(this, R.style.Theme_Screentests);
            interventionOverlay = LayoutInflater.from(wrapper).inflate(R.layout.overlay_intervention, null);

            // Layout params for an overlay that needs keyboard input
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // Removed NOT_TOUCH_MODAL to ensure input works better
                    PixelFormat.TRANSLUCENT);

            // Adjust soft input mode to push content up
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

            // Initialize UI components (chatDisplay + queenIcon are kept as fields for the observer).
            ImageView screenshotView = interventionOverlay.findViewById(R.id.lastActivityScreenshot);
            View chatContainer = interventionOverlay.findViewById(R.id.chatContainer);
            chatDisplay = interventionOverlay.findViewById(R.id.chatMessageDisplay);
            EditText chatInput = interventionOverlay.findViewById(R.id.chatEditText);
            ImageButton sendButton = interventionOverlay.findViewById(R.id.sendButton);
            queenIcon = interventionOverlay.findViewById(R.id.queenBeeIcon);

            activeSessionId = sessionId;
            lastDecision = QueenBeeUiState.Decision.NONE;

            // The Queen confronts the user with the screenshot taken earlier.
            screenshotView.setVisibility(View.VISIBLE);
            QueenBeeChatManager.getInstance().getLastScreenshot(log -> {
                if (log != null && log.screenshotBase64 != null) {
                    try {
                        byte[] decodedString = Base64.decode(log.screenshotBase64, Base64.DEFAULT);
                        Bitmap bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                        mainHandler.post(() -> screenshotView.setImageBitmap(bitmap));
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to decode screenshot", e);
                    }
                }
            });

            // Send = hand the text to the manager; ALL chat UI updates flow back via the uiState
            // observer (renderQueenUiState), so there is no duplicate UI logic here.
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
                // Render whatever the Queen chat currently holds (e.g. her opening line / thinking).
                renderQueenUiState(QueenBeeChatManager.getInstance().getUiState().getValue());
            } catch (Exception e) {
                Log.e(TAG, "Error adding intervention overlay", e);
            }
        } else {
            stopTalkingAnimation();
            chatDisplay = null;
            queenIcon = null;
            activeSessionId = null;
            lastDecision = QueenBeeUiState.Decision.NONE;
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

    // ---- Intervention entrance animation + Queen UI rendering -----------------------------

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

    /** Renders the single last line + Queen mood + any final decision. No-op when hidden. */
    private void renderQueenUiState(QueenBeeUiState s) {
        if (s == null || chatDisplay == null || queenIcon == null) return;

        // Big box shows only the latest non-empty line; crossfade when it changes.
        if (s.text != null && !s.text.isEmpty() && !s.text.equals(chatDisplay.getText().toString())) {
            crossfadeText(chatDisplay, s.text);
        }

        // Queen image: thinking, an animated talking face, or a fixed mood.
        if (s.thinking) {
            stopTalkingAnimation();
            queenIcon.setImageResource(moodToDrawable(QueenMood.THINKING));
        } else if (s.mood == QueenMood.TALKING_1 || s.mood == QueenMood.TALKING_2) {
            startTalkingAnimation();
        } else {
            stopTalkingAnimation();
            queenIcon.setImageResource(moodToDrawable(s.mood));
        }

        // Final verdict — act on it exactly once.
        if (s.decision != QueenBeeUiState.Decision.NONE && lastDecision == QueenBeeUiState.Decision.NONE) {
            lastDecision = s.decision;
            if (s.decision == QueenBeeUiState.Decision.REFILL) {
                playHoneyRefillThenReset();
            } else {
                playKickThenBlock();
            }
        }
    }

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
            case THINKING: return R.drawable.queen_thinking;
            case TALKING_1:
            default: return R.drawable.queen_talking_1;
        }
    }

    /** Refill verdict: play the 3-frame honey-fill animation, then reset the score (closes overlay). */
    private void playHoneyRefillThenReset() {
        stopTalkingAnimation();
        if (queenIcon != null) queenIcon.setImageResource(moodToDrawable(QueenMood.SHOWING_HONEY));
        final int[] frames = {R.drawable.honey_fill_1, R.drawable.honey_fill_2, R.drawable.honey_fill_3};
        for (int i = 0; i < frames.length; i++) {
            final int res = frames[i];
            mainHandler.postDelayed(() -> {
                if (queenIcon != null) queenIcon.setImageResource(res);
            }, 300L + i * 350L);
        }
        mainHandler.postDelayed(
                () -> ProductivityEngine.getInstance().resetScore(),
                300L + frames.length * 350L + 250L);
    }

    /** Kick verdict: show the Queen's outrage briefly, then block the app + send the user home. */
    private void playKickThenBlock() {
        stopTalkingAnimation();
        if (queenIcon != null) queenIcon.setImageResource(moodToDrawable(QueenMood.EXCLAIMING));
        final String pkg = activePackageName;
        mainHandler.postDelayed(
                () -> ProductivityEngine.getInstance().blockApp(pkg, KICK_BLOCK_MS),
                1200L);
    }

    private void updateCategorizationOverlay(boolean show, String packageName) {
        if (show == isCategorizationShowing && packageName.equals(lastCategorizationPackage)) return;
        Log.d(TAG, "Updating categorization overlay: show=" + show + ", pkg=" + packageName);

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
        if (isObserverRegistered) {
            ProductivityEngine.getInstance().getState().removeObserver(stateObserver);
            QueenBeeChatManager.getInstance().getUiState().removeObserver(queenUiObserver);
            isObserverRegistered = false;
        }
        stopTalkingAnimation();
    }
}
