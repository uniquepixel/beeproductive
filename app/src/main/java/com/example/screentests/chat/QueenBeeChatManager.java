package com.example.screentests.chat;

import android.content.Context;
import android.util.Log;

import com.example.screentests.database.ActivityLog;
import com.example.screentests.database.AppDatabase;
import com.example.screentests.network.ChatRequest;
import com.example.screentests.network.OpenRouterClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private Context appContext;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();

    private QueenBeeChatManager() {}

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
                    session.addAssistant(textResponse);
                    if (cb != null) cb.onReady(session, textResponse);
                }

                @Override
                public void onError(String error) {
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
        OpenRouterClient.getInstance().chat(session.snapshotMessages(), new OpenRouterClient.Callback() {
            @Override
            public void onSuccess(String textResponse) {
                session.addAssistant(textResponse);
                if (cb != null) cb.onReply(textResponse);
            }

            @Override
            public void onError(String error) {
                if (cb != null) cb.onError(error);
            }
        });
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
        }
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

        // TODO: Define the "convince" / win criteria. The user's goal is to
        // persuade the Queen Bee that it is okay that they were unproductive.
        // Once the rules are decided, describe here when the Queen should
        // concede, and any condition the manager can detect to end the session
        // as "won". Until then she simply debates and never formally concedes.
        sb.append("The user will try to convince you that it is okay that they were unproductive. ")
          .append("Engage with their arguments and push back thoughtfully. ")
          .append("Keep each reply to a few sentences.");

        return sb.toString();
    }
}
