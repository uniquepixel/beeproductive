//This class is mostly AI generated
package com.example.screentests.chat;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.screentests.database.ActivityLog;
import com.example.screentests.database.AppDatabase;
import com.example.screentests.network.ChatRequest;
import com.example.screentests.network.OpenRouterClient;
import com.example.screentests.services.TrackerAccessibilityService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    /**
     * Optional per-reply mood tag the Queen emits so the model — not just the network lifecycle —
     * drives her expression (e.g. SHOWING_HONEY, SAD, ASKING). Parsed then stripped, like the
     * decision token. See docs/QUEENBEE_UI_STATE_INTEGRATION.md.
     */
    private static final Pattern MOOD_PATTERN =
            Pattern.compile("\\[\\s*MOOD\\s*:\\s*([A-Za-z0-9_]+)\\s*\\]", Pattern.CASE_INSENSITIVE);
    /**
     * Hidden tag the Queen emits when she holds up the screenshot evidence. Parsed then stripped;
     * once seen, the screenshot stays visible for the rest of the session (carried to the frontend
     * inside {@link QueenBeeUiState}).
     */
    private static final Pattern SHOW_SCREENSHOT_PATTERN =
            Pattern.compile("\\[\\s*SHOW_SCREENSHOT\\s*\\]", Pattern.CASE_INSENSITIVE);

    private Context appContext;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final Set<String> decidedSessions = ConcurrentHashMap.newKeySet();

    /**
     * The single source of truth for the chat UI (last line + Queen mood + decision + screenshot
     * evidence). The overlay observes this; the backend can later post to it on the same basis.
     * See docs/QUEENBEE_UI_STATE_INTEGRATION.md.
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
     * Before the Queen speaks she gathers her evidence: a FRESH screenshot is
     * taken and analysed right now (falling back to the newest stored one if
     * that fails), so what she shows the user is what they were actually doing.
     *
     * Partially AI generated / Modified by AI
     *
     * @param currentScore the unproductivity score that triggered the chat
     * @param packageName  the app the user was in when the score maxed out
     */
    public ChatSession startSession(int currentScore, String packageName, StartCallback cb) {
        String sessionId = UUID.randomUUID().toString();
        // Provisional prompt (no logs yet) so the session is valid synchronously.
        ChatSession session = new ChatSession(
                sessionId, System.currentTimeMillis(), currentScore,
                buildSystemPrompt(currentScore, null, null));
        sessions.put(sessionId, session);

        // The Queen is composing her opening line; start the hard decision clock.
        postUi(session, QueenMood.THINKING, true, QueenBeeUiState.Speaker.QUEEN, "", QueenBeeUiState.Decision.NONE);
        scheduleDecisionTimeout(sessionId);

        // Step 1: secure the screenshot evidence (fresh capture, stored-log fallback).
        gatherEvidence(session, packageName, () -> dbExecutor.execute(() -> {
            // Step 2: enrich the system prompt with recent screen metadata, off the main thread.
            List<ActivityLog> recent = null;
            if (appContext != null) {
                try {
                    recent = AppDatabase.getInstance(appContext)
                            .activityLogDao().getRecentLogs(CONTEXT_LOG_COUNT);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load context logs", e);
                }
            }
            session.setSystemPrompt(buildSystemPrompt(currentScore, session, recent));

            // Step 3: ask the Queen to open the conversation. The kickoff instruction is
            // sent but NOT stored, so history starts with her greeting.
            List<ChatRequest.Message> request = session.snapshotMessages();
            request.add(new ChatRequest.Message("user",
                    "Open the conversation: greet me as the Queen Bee, present your screenshot "
                            + "evidence of what I was doing, and confront me about my unproductivity."));

            OpenRouterClient.getInstance().chat(request, new OpenRouterClient.Callback() {
                @Override
                public void onSuccess(String textResponse) {
                    String shown = handleQueenReply(sessionId, session, textResponse);
                    if (cb != null) cb.onReady(session, shown);
                }

                @Override
                public void onError(String error) {
                    handleQueenError(session, error);
                    if (cb != null) cb.onError(error);
                }
            });
        }));

        return session;
    }

    /**
     * The Queen "takes and analyses" her own evidence: captures a fresh screenshot through the
     * accessibility service, has the vision model describe it, and persists it as an
     * {@link ActivityLog}. Any failure along the way falls back to the newest stored log so the
     * session can always continue. {@code then} runs exactly once, on any thread. AI generated
     */
    private void gatherEvidence(ChatSession session, String packageName, Runnable then) {
        TrackerAccessibilityService tracker = TrackerAccessibilityService.getInstance();
        if (tracker == null) {
            fallbackToStoredEvidence(session, then);
            return;
        }
        tracker.takeScreenshotBase64(new TrackerAccessibilityService.ScreenshotCallback() {
            @Override
            public void onSuccess(String base64Image) {
                OpenRouterClient.getInstance().analyzeScreenshot(base64Image, new OpenRouterClient.Callback() {
                    @Override
                    public void onSuccess(String summary) {
                        session.setEvidence(base64Image, summary);
                        // Persist like every other screenshot so it also feeds future context.
                        if (appContext != null) {
                            dbExecutor.execute(() -> {
                                try {
                                    ActivityLog log = new ActivityLog(System.currentTimeMillis(),
                                            packageName, summary, base64Image);
                                    AppDatabase.getInstance(appContext).activityLogDao().insertLog(log);
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to persist fresh evidence log", e);
                                }
                            });
                        }
                        then.run();
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Evidence analysis failed, keeping raw screenshot: " + error);
                        // Screenshot without a summary is still worth showing.
                        session.setEvidence(base64Image, null);
                        then.run();
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "Fresh evidence capture failed: " + error);
                fallbackToStoredEvidence(session, then);
            }
        });
    }

    /** Loads the newest stored screenshot as evidence when a fresh capture is impossible. AI generated */
    private void fallbackToStoredEvidence(ChatSession session, Runnable then) {
        dbExecutor.execute(() -> {
            try {
                if (appContext != null) {
                    ActivityLog log = AppDatabase.getInstance(appContext).activityLogDao().getLastLog();
                    if (log != null) {
                        session.setEvidence(log.screenshotBase64, log.aiSummary);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load fallback evidence", e);
            }
            then.run();
        });
    }

    /**
     * Sends a user message in an existing session and returns the Queen's reply.
     * Partially AI generated / Modified by AI
     */
    public void sendMessage(String sessionId, String userText, ChatCallback cb) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) {
            if (cb != null) cb.onError("Unknown session: " + sessionId);
            return;
        }

        session.addUser(userText);
        // Show the user's line in the big box right away while the Queen thinks.
        postUi(session, QueenMood.THINKING, true, QueenBeeUiState.Speaker.USER, userText, QueenBeeUiState.Decision.NONE);

        OpenRouterClient.getInstance().chat(session.snapshotMessages(), new OpenRouterClient.Callback() {
            @Override
            public void onSuccess(String textResponse) {
                String shown = handleQueenReply(sessionId, session, textResponse);
                if (cb != null) cb.onReply(shown);
            }

            @Override
            public void onError(String error) {
                handleQueenError(session, error);
                if (cb != null) cb.onError(error);
            }
        });
    }

    // ------------------------------------------------------------------
    // UI-state broadcasting + decision handling
    // ------------------------------------------------------------------

    /**
     * Records the Queen's reply, parses & strips the hidden control tags (decision, mood,
     * show-screenshot), and broadcasts the new UI state. Returns the text that should actually
     * be shown (tags removed). Partially AI generated / Modified by AI
     */
    private String handleQueenReply(String sessionId, ChatSession session, String rawText) {
        String text = rawText != null ? rawText : "";
        QueenBeeUiState.Decision decision = QueenBeeUiState.Decision.NONE;

        Matcher dm = DECISION_PATTERN.matcher(text);
        if (dm.find()) {
            decision = "REFILL".equalsIgnoreCase(dm.group(1))
                    ? QueenBeeUiState.Decision.REFILL : QueenBeeUiState.Decision.KICK;
            text = dm.replaceAll("").trim();
        }

        // The model may also pick its own expression via a hidden [MOOD: X] tag; parse & strip it.
        QueenMood modelMood = null;
        Matcher mm = MOOD_PATTERN.matcher(text);
        if (mm.find()) {
            modelMood = parseMood(mm.group(1));
            text = mm.replaceAll("").trim();
        }

        // [SHOW_SCREENSHOT]: the Queen holds up her evidence. Sticky for the rest of the session.
        Matcher sm = SHOW_SCREENSHOT_PATTERN.matcher(text);
        if (sm.find()) {
            session.markScreenshotRevealed();
            text = sm.replaceAll("").trim();
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

        postUi(session, pickMood(modelMood, decision), false, QueenBeeUiState.Speaker.QUEEN, text, decision);
        return text;
    }

    /**
     * Chooses the Queen's expression for a reply. A mood the model picked wins; otherwise we fall
     * back to the decision-driven defaults (and plain talking when neither applies), preserving the
     * original network-lifecycle behaviour for models that don't emit a mood tag.
     * Partially AI generated / Modified by AI
     */
    private QueenMood pickMood(QueenMood modelMood, QueenBeeUiState.Decision decision) {
        if (modelMood != null) return modelMood;
        // SHOWING_HONEY is the "here is your refilled honey" pose — the natural REFILL default.
        if (decision == QueenBeeUiState.Decision.REFILL) return QueenMood.SHOWING_HONEY;
        if (decision == QueenBeeUiState.Decision.KICK) return QueenMood.EXCLAIMING;
        return QueenMood.TALKING_1;
    }

    /**
     * Maps a raw mood-tag value to a {@link QueenMood}. Unknown values and THINKING (which is
     * reserved for the network lifecycle) return null so the caller falls back to its default.
     */
    private QueenMood parseMood(String raw) {
        if (raw == null) return null;
        try {
            QueenMood mood = QueenMood.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return mood == QueenMood.THINKING ? null : mood;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    //Partially AI generated / Modified by AI
    private void handleQueenError(ChatSession session, String error) {
        postUi(session, QueenMood.ASKING, false, QueenBeeUiState.Speaker.QUEEN,
                "The Queen is speechless: " + error, QueenBeeUiState.Decision.NONE);
    }

    /**
     * Broadcasts a UI state. The screenshot evidence rides along on every post once the Queen has
     * revealed it, so the frontend can render it purely from this LiveData.
     * Partially AI generated / Modified by AI
     */
    private void postUi(ChatSession session, QueenMood mood, boolean thinking,
                        QueenBeeUiState.Speaker speaker, String text, QueenBeeUiState.Decision decision) {
        boolean show = session != null && session.isScreenshotRevealed();
        String shot = session != null ? session.getEvidenceScreenshot() : null;
        String caption = session != null ? session.getEvidenceSummary() : null;
        uiState.postValue(new QueenBeeUiState(mood, thinking, speaker, text, decision,
                show && shot != null, shot, caption));
    }

    /**
     * Arms the hard 2-minute clock; on expiry with no decision the user is kicked out (fallback).
     * Partially AI generated / Modified by AI
     */
    private void scheduleDecisionTimeout(String sessionId) {
        ScheduledFuture<?> f = timeoutExecutor.schedule(() -> {
            if (decidedSessions.add(sessionId)) {
                postUi(sessions.get(sessionId), QueenMood.EXCLAIMING, false, QueenBeeUiState.Speaker.QUEEN,
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
     *
     * Kept for API compatibility — the intervention overlay now receives the
     * screenshot through {@link #getUiState()} instead of pulling it here.
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

    /**
     * Builds the Queen's system prompt from everything the backend knows: the score that triggered
     * the chat, the screenshot evidence held by the session (image + AI description), and the
     * recent activity-log summaries. Also defines the hidden control tags (mood, show-screenshot,
     * decision) the frontend reacts to. Partially AI generated / Modified by AI
     */
    private String buildSystemPrompt(int score, ChatSession session, List<ActivityLog> recentLogs) {
        String evidenceSummary = session != null ? session.getEvidenceSummary() : null;
        boolean hasScreenshot = session != null && session.getEvidenceScreenshot() != null;

        StringBuilder sb = new StringBuilder();
        sb.append("You are the Queen Bee, the stern but FAIR ruler of the user's productivity hive. ")
          .append("The user has reached the maximum unproductivity score of ").append(score)
          .append("/100, which is why this conversation has started.\n\n")
          .append("Speak in the first person as the Queen Bee. Confront the user about their ")
          .append("unproductivity in a sharp, witty, slightly theatrical way, but never cruel. ")
          .append("Keep each reply to a few sentences.\n\n");

        // --- Evidence section -------------------------------------------------
        sb.append("YOUR EVIDENCE:\n");
        if (hasScreenshot) {
            sb.append("You are holding ONE screenshot that was just taken of the user's screen. ");
            if (evidenceSummary != null && !evidenceSummary.isEmpty()) {
                sb.append("An assistant described it as: \"").append(evidenceSummary).append("\". ");
            } else {
                sb.append("No description of it is available, so only say that you have it — ")
                  .append("do not guess what is on it. ");
            }
            sb.append("\n");
        } else {
            sb.append("No screenshot is available this time, so you have no visual evidence — ")
              .append("be upfront about that and do NOT pretend to have one.\n");
        }
        if (recentLogs != null && !recentLogs.isEmpty()) {
            sb.append("Earlier observations of their screen (most recent first, with time):\n");
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            for (ActivityLog log : recentLogs) {
                if (log == null || log.aiSummary == null) continue;
                sb.append("- ").append(fmt.format(new Date(log.timestamp)))
                  .append(" [").append(log.packageName != null ? log.packageName : "unknown app")
                  .append("] ").append(log.aiSummary).append("\n");
            }
        }
        sb.append("\n");

        // --- Rules of evidence -------------------------------------------------
        sb.append("RULES OF EVIDENCE (very important):\n")
          .append("- Only claim what the evidence above actually shows. NEVER invent or exaggerate ")
          .append("behaviour — do not say things like \"you have been scrolling endlessly\" if the ")
          .append("evidence only shows, say, three minutes of an educational video.\n")
          .append("- Reference the screenshot concretely: name what is visible on it.\n")
          .append("- If the evidence looks harmless, educational or even useful, acknowledge that ")
          .append("openly and be correspondingly lenient.\n\n");

        // --- Showing the screenshot ---------------------------------------------
        if (hasScreenshot) {
            sb.append("SHOWING THE SCREENSHOT:\n")
              .append("In your FIRST reply you MUST present the screenshot to the user by including ")
              .append("the hidden tag [SHOW_SCREENSHOT] somewhere in that reply. The app then ")
              .append("displays the screenshot on their screen, so talk about it as something you ")
              .append("are literally holding up (\"look at this...\"). Like the other tags, never ")
              .append("mention or explain the tag itself in your prose.\n\n");
        }

        // --- Mood tags ------------------------------------------------------------
        sb.append("Begin EVERY reply with a hidden mood tag in the exact form [MOOD: X], where X is one ")
          .append("of: TALKING_1, TALKING_2, ASKING, EXCLAIMING, SAD, HAPPY, SHOWING_HONEY. Choose the ")
          .append("one that matches your tone in that reply — ASKING when you pose a question, ")
          .append("EXCLAIMING when stern or outraged, SAD when disappointed, HAPPY when pleased, ")
          .append("and TALKING_1 or TALKING_2 for ordinary speech. SHOWING_HONEY means you are ")
          .append("handing the user their freshly REFILLED honey — it is a reward pose, used when ")
          .append("you grant a refill, NOT for showing evidence of unproductiveness. The mood tag is ")
          .append("a hidden control signal: never mention or explain it in your prose.\n\n");

        // --- Verdict ------------------------------------------------------------
        sb.append("YOUR VERDICT:\n")
          .append("The user will try to convince you that it is okay that they were unproductive, or ")
          .append("ask for some extra time in their app. Engage with their arguments honestly. Be ")
          .append("GENUINELY persuadable — this is a negotiation, not a sentencing: if they give any ")
          .append("reasonable justification (the content was educational or work-related, they ")
          .append("needed a short break, they make a credible promise to get back to work), grant ")
          .append("them the refill. It should NOT be nearly impossible to win extra time. Reserve a ")
          .append("kick for users who are dismissive, rude, or offer nothing at all after a couple ")
          .append("of exchanges.\n")
          .append("The instant you decide to refill their honey, end that reply with the exact token ")
          .append("[DECISION: REFILL] (and celebrate it — that reply is a good place for ")
          .append("[MOOD: SHOWING_HONEY]). If you decide they truly must stop now, end that reply ")
          .append("with the exact token [DECISION: KICK]. Emit a token ONLY once you have actually ")
          .append("decided, and never mention or explain the token in your prose — it is a hidden ")
          .append("control signal that ends the conversation.");

        return sb.toString();
    }
}
