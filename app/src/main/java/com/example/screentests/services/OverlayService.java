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
import com.example.screentests.chat.ChatSession;
import com.example.screentests.chat.QueenBeeChatManager;
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

    public enum QueenMood {
        THINKING, EXCLAIMING, TALKING, ASKING
    }

    private final Observer<ProductivityState> stateObserver = state -> {
        Log.d(TAG, "Received state update: score=" + state.getScore() + ", level=" + state.getLevel() + ", showIntervention=" + state.isShowInterventionOverlay());
        updateBees(state.getLevel());
        updateIntervention(state.isShowInterventionOverlay(), state.getQueenBeeSessionId());
        updateCategorizationOverlay(state.isCheckRequiredForUnknownApp(), state.getCurrentPackageName());
    };

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

            // Initialize UI components
            ImageView screenshotView = interventionOverlay.findViewById(R.id.lastActivityScreenshot);
            TextView chatDisplay = interventionOverlay.findViewById(R.id.chatMessageDisplay);
            EditText chatInput = interventionOverlay.findViewById(R.id.chatEditText);
            ImageButton sendButton = interventionOverlay.findViewById(R.id.sendButton);
            ImageView queenIcon = interventionOverlay.findViewById(R.id.queenBeeIcon);

            // Load last screenshot
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

            // If we have an existing session, restore its last message
            if (sessionId != null) {
                ChatSession session = QueenBeeChatManager.getInstance().getSession(sessionId);
                if (session != null && !session.getHistory().isEmpty()) {
                    chatDisplay.setText(session.getHistory().get(session.getHistory().size() - 1).text);
                }
            }

            // Send logic
            Runnable sendAction = () -> {
                String text = chatInput.getText().toString().trim();
                if (!text.isEmpty() && sessionId != null) {
                    // Show user reply immediately while Queen is "thinking"
                    chatDisplay.setText(text);
                    chatInput.setText("");
                    setQueenMood(queenIcon, QueenMood.THINKING);

                    QueenBeeChatManager.getInstance().sendMessage(sessionId, text, new QueenBeeChatManager.ChatCallback() {
                        @Override
                        public void onReply(String assistantText) {
                            mainHandler.post(() -> {
                                chatDisplay.setText(assistantText);
                                setQueenMood(queenIcon, QueenMood.TALKING);
                            });
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Chat error: " + error);
                            mainHandler.post(() -> {
                                chatDisplay.setText("The Queen is speechless: " + error);
                                setQueenMood(queenIcon, QueenMood.ASKING);
                            });
                        }
                    });
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
            } catch (Exception e) {
                Log.e(TAG, "Error adding intervention overlay", e);
            }
        } else {
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

    private void setQueenMood(ImageView icon, QueenMood mood) {
        if (icon == null) return;
        int resId;
        switch (mood) {
            case THINKING:
                resId = android.R.drawable.stat_notify_chat;
                break;
            case EXCLAIMING:
                resId = android.R.drawable.ic_dialog_alert;
                break;
            case ASKING:
                resId = android.R.drawable.ic_menu_help;
                break;
            case TALKING:
            default:
                resId = android.R.drawable.ic_dialog_info;
                break;
        }
        icon.setImageResource(resId);
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
            isObserverRegistered = false;
        }
    }
}
