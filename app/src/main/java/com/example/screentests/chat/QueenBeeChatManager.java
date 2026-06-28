package com.example.screentests.chat;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.screentests.database.ActivityLog;
import com.example.screentests.database.AppDatabase;
import com.example.screentests.network.ChatRequest;
import com.example.screentests.network.OpenRouterClient;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public entry point for the Queen Bee chat. The frontend only needs this
 * class plus {@link ChatMessage}.
 *
 * Threading: OpenRouter replies (onReady / onReply) are delivered on the
 * Android main thread by Retrofit, so they are safe for UI. The
 * {@link #getLastScreenshot} callback runs on a background thread, so wrap
 * any UI work in it with runOnUiThread(...). See docs/FRONTEND_INTEGRATION.md.
 *
 * Sessions are kept in memory only and are lost if the process dies.
 */
public class QueenBeeChatManager {
    private static final String TAG = "QueenBeeChatManager";
    /** How many recent screen summaries to feed the Queen as context. */
    private static final int CONTEXT_LOG_COUNT = 5;

    private static QueenBeeChatManager instance;

    /** Hard limit: the Queen must decide within this long of the session opening. */
    private static final long DECISION_TIMEOUT_MS = 2 * 60 * 1000L;
    /** The Queen ends a reply with this token once she has decided; we parse it then strip it. */
    private static final Pattern DECISION_PATTERN =
            Pattern.compile("\\[\\s*DECISION\\s*:\\s*(REFILL|KICK)\\s*\\]", Pattern.CASE_INSENSITIVE);

    private Context appContext;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final Set<String> decidedSessions = ConcurrentHashMap.newKeySet();

    /**
     * The single source of truth for the chat UI (last line + Queen mood + decision). The overlay
     * observes this; the backend can later post to it on the same basis. See
     * docs/QUEENBEE_UI_STATE_INTEGRATION.md.
     */
    private final MutableLiveData<QueenBeeUiState> uiState = new MutableLiveData<>(QueenBeeUiState.idle());

    private QueenBeeChatManager() {}

    /** Observe this to render the Queen Bee chat UI (mirrors ProductivityEngine.getState()). */
    public LiveData<QueenBeeUiState> getUiState() {
        return uiState;
    }

    public static synchronized QueenBeeChatManager getInstance() {
        if (instance == null) {
            instance = new QueenBeeChatManager();
        }
        return instance;
    }

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // Callbacks
    // ------------------------------------------------------------------

    public interface StartCallback {
        /** Fired once the Queen's opening line is ready (assistant text). */
        void onReady(ChatSession session, String openingMessage);
        void onError(String error);
    }

    public interface ChatCallback {
        void onReply(String assistantText);
        void onError(String error);
    }

    public interface LastScreenshotCallback {
        /** {@code log} is null when no screenshot has ever been recorded. */
        void onResult(ActivityLog log);
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    /**
     * Creates a new Queen Bee session and kicks off her opening message.
     * Returns the session immediately (its id is usable right away); the
     * opening line arrives asynchronously via {@code cb.onReady}.
     *
     * @param currentScore the unproductivity score that triggered the chat
     */
    public ChatSession startSession(int currentScore, StartCallback cb) {
        String sessionId = UUID.randomUUID().toString();
        // Provisional prompt (no logs yet) so the session is valid synchronously.
        ChatSession session = new ChatSession(
                sessionId, System.currentTimeMillis(), currentScore,
                buildSystemPrompt(currentScore, null));
        sessions.put(sessionId, session);

        // The Queen is composing her opening line; start the hard decision clock.
        postUi(QueenMood.THINKING, true, QueenBeeUiState.Speaker.QUEEN, "", QueenBeeUiState.Decision.NONE);
        scheduleDecisionTimeout(sessionId);

        dbExecutor.execute(() -> {
            // Enrich the system prompt with recent screen metadata, off the main thread.
            List<ActivityLog> recent = null;
            if (appContext != null) {
                try {
                    recent = AppDatabase.getInstance(appContext)
                            .activityLogDao().getRecentLogs(CONTEXT_LOG_COUNT);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load context logs", e);
                }
            }
            session.setSystemPrompt(buildSystemPrompt(currentScore, recent));

            // Ask the Queen to open the conversation. The kickoff instruction is
            // sent but NOT stored, so history starts with her greeting.
            List<ChatRequest.Message> request = session.snapshotMessages();
            request.add(new ChatRequest.Message("user",
                    "Open the conversation: greet me as the Queen Bee and begin confronting me about my unproductivity."));

            OpenRouterClient.getInstance().chat(request, new OpenRouterClient.Callback() {
                @Override
                public void onSuccess(String textResponse) {
                    String shown = handleQueenReply(sessionId, session, textResponse);
                    if (cb != null) cb.onReady(session, shown);
                }

                @Override
                public void onError(String error) {
                    handleQueenError(error);
                    if (cb != null) cb.onError(error);
                }
            });
        });

        return session;
    }

    /**
     * Sends a user message in an existing session and returns the Queen's reply.
     */
    public void sendMessage(String sessionId, String userText, ChatCallback cb) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            if (cb != null) cb.onError("Unknown session: " + sessionId);
            return;
        }

        session.addUser(userText);
        // Show the user's line in the big box right away while the Queen thinks.
        postUi(QueenMood.THINKING, true, QueenBeeUiState.Speaker.USER, userText, QueenBeeUiState.Decision.NONE);

        OpenRouterClient.getInstance().chat(session.snapshotMessages(), new OpenRouterClient.Callback() {
            @Override
            public void onSuccess(String textResponse) {
                String shown = handleQueenReply(sessionId, session, textResponse);
                if (cb != null) cb.onReply(shown);
            }

            @Override
            public void onError(String error) {
                handleQueenError(error);
                if (cb != null) cb.onError(error);
            }
        });
    }

    // ------------------------------------------------------------------
    // UI-state broadcasting + decision handling
    // ------------------------------------------------------------------

    /**
     * Records the Queen's reply, parses & strips any hidden decision token, and broadcasts the
     * new UI state. Returns the text that should actually be shown (token removed).
     */
    private String handleQueenReply(String sessionId, ChatSession session, String rawText) {
        String text = rawText != null ? rawText : "";
        QueenBeeUiState.Decision decision = QueenBeeUiState.Decision.NONE;

        Matcher m = DECISION_PATTERN.matcher(text);
        if (m.find()) {
            decision = "REFILL".equalsIgnoreCase(m.group(1))
                    ? QueenBeeUiState.Decision.REFILL : QueenBeeUiState.Decision.KICK;
            text = m.replaceAll("").trim();
        }
        if (text.isEmpty()) text = "..."; // never leave the box blank
        session.addAssistant(text);

        // Only honour the first decision for a session; ignore any later/duplicate token.
        if (decision != QueenBeeUiState.Decision.NONE) {
            if (decidedSessions.add(sessionId)) {
                cancelDecisionTimeout(sessionId);
            } else {
                decision = QueenBeeUiState.Decision.NONE;
            }
        }

        QueenMood mood = decision == QueenBeeUiState.Decision.REFILL ? QueenMood.HAPPY
                : decision == QueenBeeUiState.Decision.KICK ? QueenMood.EXCLAIMING
                : QueenMood.TALKING_1;
        postUi(mood, false, QueenBeeUiState.Speaker.QUEEN, text, decision);
        return text;
    }

    private void handleQueenError(String error) {
        postUi(QueenMood.ASKING, false, QueenBeeUiState.Speaker.QUEEN,
                "The Queen is speechless: " + error, QueenBeeUiState.Decision.NONE);
    }

    private void postUi(QueenMood mood, boolean thinking, QueenBeeUiState.Speaker speaker,
                        String text, QueenBeeUiState.Decision decision) {
        uiState.postValue(new QueenBeeUiState(mood, thinking, speaker, text, decision));
    }

    /** Arms the hard 2-minute clock; on expiry with no decision the user is kicked out (fallback). */
    private void scheduleDecisionTimeout(String sessionId) {
        ScheduledFuture<?> f = timeoutExecutor.schedule(() -> {
            if (decidedSessions.add(sessionId)) {
                postUi(QueenMood.EXCLAIMING, false, QueenBeeUiState.Speaker.QUEEN,
                        "Time's up. Back to work — the hive has no more patience.",
                        QueenBeeUiState.Decision.KICK);
            }
            timeouts.remove(sessionId);
        }, DECISION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        timeouts.put(sessionId, f);
    }

    private void cancelDecisionTimeout(String sessionId) {
        ScheduledFuture<?> f = timeouts.remove(sessionId);
        if (f != null) f.cancel(false);
    }

    public ChatSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /** Conversation so far (user + assistant turns). Empty list for unknown ids. */
    public List<ChatMessage> getHistory(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) return new java.util.ArrayList<>();
        return session.getHistory();
    }

    public void endSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
            cancelDecisionTimeout(sessionId);
            decidedSessions.remove(sessionId);
        }
        // Reset the UI channel so a future session starts clean.
        uiState.postValue(QueenBeeUiState.idle());
    }

    // ------------------------------------------------------------------
    // Screenshots
    // ------------------------------------------------------------------

    /**
     * Retrieves the most recent screenshot together with its metadata
     * (timestamp, package name, AI summary, base64 image). The callback runs
     * on a background thread.
     */
    public void getLastScreenshot(LastScreenshotCallback cb) {
        if (cb == null) return;
        dbExecutor.execute(() -> {
            ActivityLog log = null;
            if (appContext != null) {
                try {
                    log = AppDatabase.getInstance(appContext).activityLogDao().getLastLog();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load last screenshot", e);
                }
            }
            cb.onResult(log);
        });
    }

    // ------------------------------------------------------------------
    // Prompt building
    // ------------------------------------------------------------------

    private String buildSystemPrompt(int score, List<ActivityLog> recentLogs) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the Queen Bee, the stern but fair ruler of the user's productivity hive. ")
          .append("The user has reached the maximum unproductivity score of ").append(score)
          .append("/100, which is why this conversation has started.\n\n")
          .append("Speak in the first person as the Queen Bee. Confront the user about their ")
          .append("unproductivity in a sharp, witty, slightly theatrical way, but never cruel. ")
          .append("Reference the specific things they were doing (listed below) so it feels personal.\n\n");

        if (recentLogs != null && !recentLogs.isEmpty()) {
            sb.append("Here is what the user was recently seen doing on their screen (most recent first):\n");
            for (ActivityLog log : recentLogs) {
                if (log == null || log.aiSummary == null) continue;
                sb.append("- [").append(log.packageName != null ? log.packageName : "unknown app")
                  .append("] ").append(log.aiSummary).append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("(No recent screen summaries are available.)\n\n");
        }

        sb.append("The user will try to convince you that it is okay that they were unproductive. ")
          .append("Engage with their arguments and push back thoughtfully. ")
          .append("Keep each reply to a few sentences.\n\n")
          .append("You must eventually reach a verdict. The instant you are genuinely convinced and ")
          .append("decide to refill their honey, end that reply with the exact token ")
          .append("[DECISION: REFILL]. If they are dismissive, or you decide they truly must stop now, ")
          .append("end that reply with the exact token [DECISION: KICK]. Emit a token ONLY once you ")
          .append("have actually decided, and never mention or explain the token in your prose — it is ")
          .append("a hidden control signal that ends the conversation.");

        return sb.toString();
    }
}
