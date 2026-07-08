# Queen Bee Chat — Frontend Integration Guide

This guide is for the **frontend** of beeproductive. You don't need to touch the
backend, the network code, or the database — everything you need is in two small
classes:

- **`ProductivityEngine`** — tells you *when* to open the chat (via the state it
  already broadcasts).
- **`QueenBeeChatManager`** — lets you *run* the chat: start it, send messages,
  read history, end it. Also gives you the last screenshot.

> **One rule to remember:** all the callbacks below are **asynchronous**. Some
> arrive on a background thread. **Always update UI inside
> `runOnUiThread(...)`** (Activity) or `view.post(...)` (any View). Examples
> below do this for you.

---

## The big picture

```
score climbs to 100
        │
        ▼
ProductivityEngine sets state.isShowQueenBeeChat() = true
   and state.getQueenBeeSessionId() = "abc-123"
        │  (you observe this)
        ▼
You open your chat screen with that sessionId
        │
        ▼
QueenBeeChatManager.getHistory(sessionId)   → show the Queen's opening line
QueenBeeChatManager.sendMessage(...)         → user talks, Queen replies
QueenBeeChatManager.endSession(sessionId)    → when the user is done
```

The backend **auto-creates** the session the moment the score hits 100, so by the
time you see `isShowQueenBeeChat() == true`, the session already exists and the
Queen's opening message is on its way (or already there).

---

## Step 1 — Know when to open the chat

`ProductivityEngine` exposes a `LiveData<ProductivityState>`. Observe it and react
to the new `isShowQueenBeeChat()` flag.

```java
ProductivityEngine.getInstance().getState().observe(this, state -> {
    if (state.isShowQueenBeeChat()) {
        // Score hit 100 — open the chat UI for this session.
        String sessionId = state.getQueenBeeSessionId();
        openQueenBeeChat(sessionId);   // <-- your screen / fragment / dialog
    }
});
```

`ProductivityState` getters you'll use:

| Getter | Meaning |
|---|---|
| `boolean isShowQueenBeeChat()` | `true` when the score is at the max and the chat should be open. |
| `String getQueenBeeSessionId()` | The session id to pass to `QueenBeeChatManager`. `null` when no chat is active. |
| `int getScore()` | Current unproductivity score (0–100), if you want to show it. |

> Tip: keep your own `boolean isChatOpen` so you only open the screen once (the
> flag can stay `true` across several state updates).

---

## Step 2 — Show the opening line / existing history

When your chat screen opens, render whatever the conversation already has. The
Queen's greeting may take a second to arrive (it's a network call), so render
what's there now and refresh when a new message comes in.

```java
void openQueenBeeChat(String sessionId) {
    this.sessionId = sessionId;
    renderHistory();   // show whatever exists so far (maybe empty for a moment)
}

void renderHistory() {
    List<ChatMessage> history = QueenBeeChatManager.getInstance().getHistory(sessionId);
    chatAdapter.submit(history);   // your RecyclerView/list
}
```

`ChatMessage` is dead simple:

```java
public class ChatMessage {
    public final String role;   // "user" or "assistant"  (assistant = the Queen)
    public final String text;
    public final long   timestamp;
}
```

> The Queen's opening message arrives asynchronously. The easiest pattern is:
> after opening the screen, just call `renderHistory()` again once you send your
> first message (Step 3), or poll `getHistory()` on a short timer / after a
> pull-to-refresh. If you want the greeting to appear instantly, ask the backend
> dev to expose the `StartCallback` to you — but for most UIs Step 3's refresh is
> enough.

---

## Step 3 — Send a message and get the Queen's reply

This is the core loop. Add the user's bubble immediately, then append the Queen's
reply when it arrives.

```java
void onUserSend(String userText) {
    // 1) Show the user's message right away.
    chatAdapter.add(new ChatMessage("user", userText, System.currentTimeMillis()));

    // 2) Ask the Queen.
    QueenBeeChatManager.getInstance().sendMessage(sessionId, userText,
        new QueenBeeChatManager.ChatCallback() {
            @Override
            public void onReply(String queenText) {
                runOnUiThread(() ->
                    chatAdapter.add(new ChatMessage("assistant", queenText, System.currentTimeMillis())));
            }

            @Override
            public void onError(String err) {
                runOnUiThread(() -> showError(err));   // e.g. a toast / retry bubble
            }
        });
}
```

That's it. You don't manage history yourself — the manager remembers the whole
conversation for this `sessionId`, so each `sendMessage` already includes all the
previous turns. You can always re-sync your list with
`QueenBeeChatManager.getInstance().getHistory(sessionId)`.

---

## Step 4 — End the chat

Call `endSession` when the user closes/finishes the chat. The conversation is then
forgotten.

```java
QueenBeeChatManager.getInstance().endSession(sessionId);
```

Note: the existing **"Back to Focus"** intervention button (which calls
`ProductivityEngine.resetScore()`) **already ends the session for you**. So if your
chat lives behind that overlay, you may not need to call `endSession` at all —
just don't double-end.

---

## Step 5 — Show the last screenshot (+ metadata)

`getLastScreenshot` returns the most recent captured screen, with the AI's
one-line summary and metadata. **This callback runs on a background thread**, so
hop to the UI thread before touching views.

```java
QueenBeeChatManager.getInstance().getLastScreenshot(log -> runOnUiThread(() -> {
    if (log == null || log.screenshotBase64 == null) {
        // No screenshot has been captured yet.
        return;
    }

    // Decode the base64 JPEG into a Bitmap.
    byte[] bytes = Base64.decode(log.screenshotBase64, Base64.NO_WRAP);
    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

    screenshotImageView.setImageBitmap(bmp);
    summaryLabel.setText(log.aiSummary);                 // e.g. "Scrolling a video feed"
    packageLabel.setText(log.packageName);               // e.g. "com.instagram.android"
    timeLabel.setText(formatTime(log.timestamp));        // millis since epoch
}));
```

`ActivityLog` (what you get back) fields:

| Field | Type | Meaning |
|---|---|---|
| `timestamp` | `long` | When the screenshot was taken (ms since epoch). |
| `packageName` | `String` | App that was on screen. |
| `aiSummary` | `String` | One-sentence AI description of the screen. |
| `screenshotBase64` | `String` | The JPEG image, base64-encoded (decode as above). |

---

## Full API reference — `QueenBeeChatManager`

Get the singleton with `QueenBeeChatManager.getInstance()`.

| Method | What it does |
|---|---|
| `ChatSession startSession(int score, String packageName, StartCallback cb)` | Starts a new chat and kicks off the Queen's opening line. The Queen first takes + analyses a **fresh screenshot** as her evidence (falling back to the newest stored one). **The backend calls this automatically at score 100** — you normally don't. Returns the session immediately; `cb.onReady(session, openingText)` fires when the greeting is ready. |
| `void sendMessage(String sessionId, String userText, ChatCallback cb)` | Sends a user message; `cb.onReply(text)` returns the Queen's response. |
| `List<ChatMessage> getHistory(String sessionId)` | The conversation so far (user + Queen turns). Empty list for an unknown id. |
| `ChatSession getSession(String sessionId)` | The raw session object (id, `createdAt`, `scoreSnapshot`). |
| `void endSession(String sessionId)` | Forgets the conversation. |
| `void getLastScreenshot(LastScreenshotCallback cb)` | Returns the latest `ActivityLog` (image + metadata). **Callback is off the main thread.** Kept for compatibility — the intervention overlay now receives the screenshot through `getUiState()` instead (see QUEENBEE_UI_STATE_INTEGRATION.md). |

Callback interfaces:

```java
interface StartCallback        { void onReady(ChatSession s, String openingMessage); void onError(String e); }
interface ChatCallback         { void onReply(String assistantText); void onError(String e); }
interface LastScreenshotCallback { void onResult(ActivityLog log); }  // log may be null
```

---

## Gotchas (read this)

- **Threading.** `onReply` / `onReady` come back on the **main thread** (safe for
  UI), but `getLastScreenshot`'s callback comes back on a **background thread** —
  wrap its UI work in `runOnUiThread(...)`. When in doubt, always wrap.
- **Sessions are in-memory only.** If the app process is killed, the conversation
  is gone. Don't rely on history surviving a restart.
- **`getQueenBeeSessionId()` can be `null`** — only use it when
  `isShowQueenBeeChat()` is `true`.
- **The "convince the Queen" win condition IS implemented now.** The Queen ends the
  debate with a hidden `[DECISION: REFILL]` / `[DECISION: KICK]` token that arrives
  as `QueenBeeUiState.decision` — see QUEENBEE_UI_STATE_INTEGRATION.md.
- **No API key = errors.** The chat needs `OPENROUTER_API_KEY` set in
  `local.properties` (backend setup). If it's missing you'll get `onError`
  callbacks — show a friendly message rather than crashing.
