package com.example.screentests.services;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.example.screentests.R;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.engine.ProductivityState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Owns the bee swarm overlay. The swarm is fully driven by the productivity score it reads
 * directly from {@link ProductivityEngine}: more unproductivity -> more bees, angrier bees,
 * bees sloshing in from off-screen, a brief screen-swarm near the top, and a cover-then-vanish
 * during the intervention. Only this class and {@link SingleBee} contain swarm logic;
 * OverlayService still drives these only through initBeeSwarm/startSimulation/removeAllBees.
 */
public class BeeManager {
    public enum dim {WIDTH, HEIGHT}

    //Score thresholds!
    private static final String TAG = "BeeManager";
    private static final int SCORE_MAX = 100;       //this should really not be changed
    private static final int AMBIENT_SCORE = 20;   //below: no bees (matches engine level 0)
    private static final int SLOSH_START = 35;     //above: bees start dipping into view
    private static final int ANGER_START = 65;     //above: angriness ramps up
    private static final int SWIPE_LAYER_SCORE = 70; //above: swipe-to-disperse layer is live
    private static final int NEST_SCORE = 85;      //above: swipes stir up the "nest" -> "consumes" touches!! Would need a rework for an actual application
    private static final int SWARM_EVENT_SCORE = 90; //brief all-in swarm event (below max)

    //Behaviour tuning
    private static final double ORBIT_RADIUS_FACTOR = 0.75; // ring radius vs half-screen -> mostly off-screen
    private static final double ORBIT_SPEED = 0.02;         //radians per frame -> frame rate dependant (not good but whatever)
    private static final double SLOSH_INNER_FACTOR = 0.35;  //how far inside the screen a sloshing bee dips
    private static final double OFFSCREEN_GOAL_FACTOR = 1.5; //despawn goal distance vs screen size
    private static final long SWARM_EVENT_DURATION_MS = 1800; //after swarm_event_score
    private static final long INTERVENTION_COVER_MS = 1600;  //how long bees stay on the intervention screen
    private static final long NEST_BOOST_MS = 2500;         //after shaking away angry bees, how long does it take until more, angrier ones appear
    private static final double SWIPE_IMPULSE = 45.0;       //for bee goal vector calc

    //Edge fade: a bee becomes fully transparent within this fraction
    // of the smaller screen side, because i was too dumb to actually make them go off screen
    //Greater values mean larger fade area, not stronger fade
    private static final double EDGE_FADE_FRACTION = 1;
    //Shake/swipe flee: bees rush to the edge and linger there. Linger is long at low score,
    //short when the unproductivity is severe.
    private static final long FLEE_LINGER_MAX_MS = 5000;
    private static final long FLEE_LINGER_MIN_MS = 1000;

    private static final int FRAME_MS = 32; // ca 30 FPS

    private final Context context;
    private final WindowManager windowManager;
    private final int maxVisualOverhead = 100;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    // bees is the single source of truth, mutated only under beesLock.
    private final Object beesLock = new Object();
    private final List<SingleBee> bees = new ArrayList<>();
    private final Set<SingleBee> despawning = new HashSet<>();
    // beeViews is only ever touched on the main thread.
    private final Map<SingleBee, View> beeViews = new HashMap<>();

    private volatile boolean isSimulationRunning = false; //volatile because threading is idiotic and i haven't found a better way. This makes way too many cache writes but i havent found a better way to fix the inconsistencies

    //cached at simulation start so the background loop doesnt touch windowmanager for metrics
    private int screenW = 1;
    private int screenH = 1;

    private long frame = 0;

    //for the swarming event
    private boolean swarmEventArmed = true;
    private boolean swarmEventActive = false;
    private long swarmEventEndTime = 0;

    //for intervention
    private boolean interventionVanishing = false;
    private long interventionCoverEndTime = 0;
    private boolean lastIntervention = false;

    //the infamous touch consuming swipe layer (only touched on the main thread)
    private View swipeLayer = null;
    private boolean swipeLayerDesired = false; //simthread view of whether the layer should exist
    // Written from the swipe callback (main thread), read by the sim loop -> volatile for safe publication.
    private volatile long nestBoostEndTime = 0;

    //for user interaction states (overwritten by gesture callbacks)
    private volatile long fleeEndTime = 0;
    private volatile boolean fleeRadial = false; // true: scatter radially (shake); false: along fleeDir (swipe)
    private volatile double fleeDirX = 0;
    private volatile double fleeDirY = 0;
    private ShakeDetector shakeDetector;

    //honey bottle logic (for shortly displayed container)
    private int lastHoneyBand = 0;     //0 none, 1 full, 2 medium, 3 low -> enum wouldve been smarter but whatever
    private View honeyBottleView = null; //only touched on the main thread

    public BeeManager(Context context, WindowManager windowManager, int score) {
        this.context = context;
        this.windowManager = windowManager;
        this.shakeDetector = new ShakeDetector(context, this::onShake);//AI suggested this sorcery and i am frankly amazed it works. However, I formally distance myself from the wording "this::onShake".
    }

    public int getWindowSize(dim dimension) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return dimension == dim.WIDTH ? bounds.width() : bounds.height();
        } else {
            return dimension == dim.WIDTH ? windowManager.getDefaultDisplay().getWidth() : windowManager.getDefaultDisplay().getHeight();
        }
    }

    /**
     * Kept for the OverlayService contract. Bee count is now derived from the score, so this
     * just ensures the score-driven simulation is running (no destructive repopulate, which was
     * the source of the old race condition).
     */
    public void initBeeSwarm(int level) {
        Log.d(TAG, "initBeeSwarm hint level: " + level);
        startSimulation();
    }

    public void startSimulation() {
        if (isSimulationRunning) return;
        isSimulationRunning = true;

        // Snapshot screen size once on the calling (main) thread.
        screenW = Math.max(1, getWindowSize(dim.WIDTH));
        screenH = Math.max(1, getWindowSize(dim.HEIGHT));

        // Listen for shakes while the swarm is alive.
        if (shakeDetector != null) shakeDetector.start();

        new Thread(() -> {
            Log.d(TAG, "Simulation started");
            while (isSimulationRunning) {
                ProductivityState state = ProductivityEngine.getInstance().getState().getValue();
                int score = state != null ? state.getScore() : 0;
                boolean intervention = (state != null && state.isShowInterventionOverlay());

                driveSwarm(score, intervention);

                // Physics on a stable snapshot, then ONE batched UI post for the whole frame
                // (was one main-thread post per bee per frame -> the old swipe/shake lag source).
                List<SingleBee> snapshot;
                synchronized (beesLock) {
                    snapshot = new ArrayList<>(bees);
                }
                List<BeeFrame> frames = new ArrayList<>(snapshot.size());
                for (SingleBee bee : snapshot) {
                    bee.timeStep();
                    int bx = bee.getPosition(dim.WIDTH);
                    int by = bee.getPosition(dim.HEIGHT);
                    frames.add(new BeeFrame(bee, bx, by, clampedEdgeAlpha(bx, by)));
                }
                renderBeeFrames(frames);

                reapOffScreenBees(snapshot);

                // Self-stop when fully calm and empty (OverlayService restarts us on the next level rise).
                if (score <= AMBIENT_SCORE && !intervention) {
                    boolean empty;
                    synchronized (beesLock) {
                        empty = bees.isEmpty();
                    }
                    if (empty) {
                        isSimulationRunning = false;
                    }
                }

                frame++;
                try {
                    Thread.sleep(FRAME_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            isSimulationRunning = false;
            if (shakeDetector != null) shakeDetector.stop();
            Log.d(TAG, "Simulation stopped");
        }).start();
    }

    /**
     * The brain: reads the score and updates target count, goals, angriness and special events.
     */
    private void driveSwarm(int score, boolean intervention) {
        long now = System.currentTimeMillis();

        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;
        double ringX = screenW * ORBIT_RADIUS_FACTOR;
        double ringY = screenH * ORBIT_RADIUS_FACTOR;

        double angriness = clamp01((score - ANGER_START) / (double) (SCORE_MAX - ANGER_START));
        double slosh = clamp01((score - SLOSH_START) / (double) (SCORE_MAX - SLOSH_START));

        // --- Intervention state machine: cover the screen, then vanish ---
        if (intervention && !lastIntervention) {
            // Rising edge: start the cover phase.
            interventionVanishing = false;
            interventionCoverEndTime = now + INTERVENTION_COVER_MS;
        }
        if (!intervention) {
            interventionVanishing = false;
        }
        lastIntervention = intervention;

        boolean interventionCovering = intervention && now < interventionCoverEndTime && !interventionVanishing;
        if (intervention && now >= interventionCoverEndTime) {
            interventionVanishing = true;
        }

        // --- Brief swarm event (only below max; the intervention owns the top) ---
        if (!intervention) {
            if (score >= SWARM_EVENT_SCORE) {
                if (swarmEventArmed && !swarmEventActive) {
                    swarmEventActive = true;
                    swarmEventEndTime = now + SWARM_EVENT_DURATION_MS;
                    swarmEventArmed = false;
                    Log.d(TAG, "Swarm event triggered");
                }
            } else if (score < SWARM_EVENT_SCORE - 5) {
                swarmEventArmed = true; // re-arm once we've dropped back down
            }
            if (swarmEventActive && now >= swarmEventEndTime) {
                swarmEventActive = false;
            }
        } else {
            swarmEventActive = false;
        }

        boolean coverScreen = interventionCovering || swarmEventActive;
        boolean nestBoost = now < nestBoostEndTime;
        double effectiveAngriness = nestBoost ? Math.max(angriness, 0.9)
                : (coverScreen ? Math.max(angriness, 0.6) : angriness);

        // --- Target bee count from score ---
        int maxBees = ProductivityEngine.getInstance().getMaxBees();
        int targetCount;
        if (intervention && interventionVanishing) {
            targetCount = 0; // vanish phase
        } else if (coverScreen) {
            targetCount = maxBees; // fill the screen
        } else if (score <= AMBIENT_SCORE) {
            targetCount = 0;
        } else {
            double t = (score - AMBIENT_SCORE) / (double) (SCORE_MAX - AMBIENT_SCORE);
            targetCount = (int) Math.round(maxBees * t);
        }
        targetCount = Math.max(0, Math.min(maxBees, targetCount));

        boolean structureChanged = adjustPopulation(targetCount, centerX, centerY, ringX, ringY, nestBoost);

        // --- Assign goals + angriness for this frame ---
        // Snapshot bees + despawning together under one lock (was a lock per bee = swipe lag).
        List<SingleBee> snapshot;
        Set<SingleBee> despawningSnapshot;
        synchronized (beesLock) {
            snapshot = new ArrayList<>(bees);
            despawningSnapshot = new HashSet<>(despawning);
        }
        boolean fleeing = now < fleeEndTime;
        for (SingleBee bee : snapshot) {
            if (despawningSnapshot.contains(bee)) {
                setOffScreenGoal(bee, centerX, centerY, ringX, ringY);
                bee.setAngriness(effectiveAngriness);
                continue;
            }
            if (fleeing) {
                // Shaken/swiped: rush to the nearest edge and hold there until fleeEndTime.
                setFleeGoal(bee, centerX, centerY);
                bee.setAngriness(effectiveAngriness);
                continue;
            }

            double gx, gy;
            if (coverScreen) {
                // Spread across the whole visible screen (stable per-bee via phase).
                gx = screenW * mapUnit(frac(bee.getPhase()));
                gy = screenH * mapUnit(frac(bee.getPhase() * 1.6180339887));
            } else {
                double angle = bee.getPhase() + frame * ORBIT_SPEED * (1 + effectiveAngriness);
                double radiusFactor = 1.0;
                // Sloshing: a slow per-bee wave occasionally pulls the goal inside the screen.
                double wave = Math.sin(frame * 0.012 + bee.getPhase() * 3.0);
                if (slosh > 0 && wave > (1.0 - slosh)) {
                    radiusFactor = SLOSH_INNER_FACTOR;
                }
                gx = centerX + Math.cos(angle) * ringX * radiusFactor;
                gy = centerY + Math.sin(angle) * ringY * radiusFactor;
            }
            bee.setGoal(gx, gy);
            bee.setAngriness(effectiveAngriness);
        }

        if (structureChanged) {
            refreshNeighbourSnapshots();
        }

        // --- Swipe layer presence follows the score (only post on change) ---
        boolean wantSwipeLayer = score >= SWIPE_LAYER_SCORE;
        if (wantSwipeLayer != swipeLayerDesired) {
            swipeLayerDesired = wantSwipeLayer;
            setSwipeLayer(wantSwipeLayer);
        }

        // --- Honey-bottle side indicator: flash the matching jar when the honey band worsens ---
        updateHoneyIndicator(score);
    }

    /**
     * Spawns/despawns toward target count. Despawning bees are only marked here,
     * not removed until they leave the screen (handled by reapOffScreenBees).
     * Returns true if the bee list membership changed.
     */
    private boolean adjustPopulation(int targetCount, double centerX, double centerY,
                                     double ringX, double ringY, boolean nestBoost) {
        boolean changed = false;
        int spawnPerFrame = nestBoost ? 4 : 1; // nest stir-ups regather fast

        synchronized (beesLock) {
            int active = bees.size() - despawning.size();

            if (active < targetCount) {//MORE BEES
                int toSpawn = Math.min(spawnPerFrame, targetCount - active);
                for (int i = 0; i < toSpawn; i++) {
                    SingleBee bee = new SingleBee(screenW, screenH, maxVisualOverhead);
                    // Spawn off-screen on the ring so the bee flies in from the edge.
                    double angle = random.nextDouble() * Math.PI * 2;
                    bee.setPosition(centerX + Math.cos(angle) * ringX,
                            centerY + Math.sin(angle) * ringY);
                    bees.add(bee);
                    changed = true;
                }
            } else if (active > targetCount) {
                int toRemove = active - targetCount;
                for (SingleBee bee : bees) {
                    if (toRemove <= 0) break;
                    if (!despawning.contains(bee)) {
                        despawning.add(bee); // gets an off-screen goal, reaped once it leaves the screen
                        toRemove--;
                    }
                }
            }
        }
        return changed;
    }

    /** Removes the views of despawning bees that have now left the screen. */
    private void reapOffScreenBees(List<SingleBee> snapshot) {
        List<SingleBee> reaped = null;
        synchronized (beesLock) {
            for (SingleBee bee : snapshot) {
                if (despawning.contains(bee) && bee.isOffScreen()) {
                    if (reaped == null) reaped = new ArrayList<>();
                    reaped.add(bee);
                }
            }
            if (reaped != null) {
                bees.removeAll(reaped);
                despawning.removeAll(reaped);
            }
        }
        if (reaped != null) {
            final List<SingleBee> toRemove = reaped;
            mainHandler.post(() -> {
                for (SingleBee bee : toRemove) {
                    View view = beeViews.remove(bee);
                    if (view != null) {
                        try {
                            windowManager.removeView(view);
                        } catch (Exception e) {
                            Log.e(TAG, "Error removing reaped bee view", e);
                        }
                    }
                }
            });
            refreshNeighbourSnapshots();
        }
    }

    private void refreshNeighbourSnapshots() {
        synchronized (beesLock) {
            List<SingleBee> copy = new ArrayList<>(bees);
            for (SingleBee bee : bees) {
                bee.updateExistingBees(copy);
            }
        }
    }

    private void setOffScreenGoal(SingleBee bee, double centerX, double centerY, double ringX, double ringY) {
        double dx = bee.getPosition(dim.WIDTH) - centerX;
        double dy = bee.getPosition(dim.HEIGHT) - centerY;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) {//to stop bees from getting stuck in the center
            double a = random.nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dy = Math.sin(a);
            len = 1;
        }
        double gX = centerX + (dx / len) * ringX * OFFSCREEN_GOAL_FACTOR / ORBIT_RADIUS_FACTOR;
        double gY = centerY + (dy / len) * ringY * OFFSCREEN_GOAL_FACTOR / ORBIT_RADIUS_FACTOR;
        bee.setGoal(gX, gY);
    }

    /**
     * Per-bee alpha based on distance to the nearest screen edge: fully opaque inside, fading to
     * fully transparent as the bee touches the edge. Uses the bee view centre (views are 100px).
     */
    private float clampedEdgeAlpha(int x, int y) {
        double cx = x + 50.0;
        double cy = y + 50.0;
        double margin = EDGE_FADE_FRACTION * Math.min(screenW, screenH);
        if (margin < 1) margin = 1;
        double minEdge = Math.min(Math.min(cx, cy), Math.min(screenW - cx, screenH - cy));
        double a = minEdge / margin;
        if (a < 0) a = 0;
        if (a > 1) a = 1;
        return (float) a;
    }

    /** ONE batched main-thread update for the whole frame (replaces the old per-bee post). */
    private void renderBeeFrames(List<BeeFrame> frames) {
        mainHandler.post(() -> {
            if (!isSimulationRunning) return; // torn down between post and run

            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Overlay permission not granted. Stopping bee rendering.");
                removeAllBees();
                return;
            }

            for (BeeFrame f : frames) {
                View view = beeViews.get(f.bee);
                if (view == null) {
                    ImageView imageView = new ImageView(context);
                    imageView.setImageResource(R.drawable.worker_bee);
                    imageView.setAlpha(f.alpha);

                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            100, 100,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.TOP | Gravity.START;
                    params.x = f.x;
                    params.y = f.y;

                    try {
                        windowManager.addView(imageView, params);
                        beeViews.put(f.bee, imageView);
                    } catch (Exception e) {
                        Log.e(TAG, "Error adding bee view", e);
                    }
                } else {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
                    if (params.x != f.x || params.y != f.y) {
                        params.x = f.x;
                        params.y = f.y;
                        try {
                            windowManager.updateViewLayout(view, params);
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating bee layout", e);
                        }
                    }
                    if (view.getAlpha() != f.alpha) {
                        view.setAlpha(f.alpha);
                    }
                }
            }
        });
    }

    /** Lightweight per-frame render record (position + edge alpha), built on the sim thread. */
    private static final class BeeFrame {
        final SingleBee bee;
        final int x;
        final int y;
        final float alpha;

        BeeFrame(SingleBee bee, int x, int y, float alpha) {
            this.bee = bee;
            this.x = x;
            this.y = y;
            this.alpha = alpha;
        }
    }

    // --- Shake/swipe flee mechanic (unifies both gestures) ---------------------------------

    /** Which honey band a score sits in: 1 full, 2 medium, 3 low (more unproductive = less honey). */
    private int honeyBandForScore(int score) {
        if (score < SLOSH_START) return 1; // plenty of honey left
        if (score < ANGER_START) return 2; // running low
        return 3;                           // nearly empty
    }

    /** Flash the matching honey jar at the screen side when the band worsens as the score rises. */
    private void updateHoneyIndicator(int score) {
        if (score <= AMBIENT_SCORE) {
            lastHoneyBand = 0;
            return;
        }
        int band = honeyBandForScore(score);
        if (band > lastHoneyBand) {
            showHoneyBottle(band);
        }
        lastHoneyBand = band;
    }

    private void showHoneyBottle(int band) {
        final int resId = band == 1 ? R.drawable.honey_bottle_full
                : band == 2 ? R.drawable.honey_bottle_medium
                : R.drawable.honey_bottle_low;
        mainHandler.post(() -> {
            if (!isSimulationRunning) return;
            if (!Settings.canDrawOverlays(context)) return;

            mainHandler.removeCallbacks(honeyHideRunnable);
            removeHoneyBottleView();

            ImageView iv = new ImageView(context);
            iv.setImageResource(resId);
            iv.setAlpha(0f);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    200, 200,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            params.x = 8; // Move closer to the right edge

            try {
                windowManager.addView(iv, params);
                honeyBottleView = iv;
                iv.animate().alpha(1f).setDuration(300).start();
                mainHandler.postDelayed(honeyHideRunnable, 1500);
            } catch (Exception e) {
                Log.e(TAG, "Error adding honey bottle", e);
            }
        });
    }

    private final Runnable honeyHideRunnable = () -> {
        final View v = honeyBottleView;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(300).withEndAction(this::removeHoneyBottleView).start();
    };

    private void removeHoneyBottleView() {
        if (honeyBottleView != null) {
            try {
                windowManager.removeView(honeyBottleView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing honey bottle", e);
            }
            honeyBottleView = null;
        }
    }

    /** Goal that pushes a fleeing bee onto the nearest screen edge (radial for shake, fixed for swipe). */
    private void setFleeGoal(SingleBee bee, double centerX, double centerY) {
        double dx, dy;
        if (fleeRadial) {
            dx = bee.getPosX() - centerX;
            dy = bee.getPosY() - centerY;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1) {
                double a = random.nextDouble() * Math.PI * 2;
                dx = Math.cos(a);
                dy = Math.sin(a);
            } else {
                dx /= len;
                dy /= len;
            }
        } else {
            dx = fleeDirX;
            dy = fleeDirY;
        }
        bee.setGoal(centerX + dx * (screenW * 0.5), centerY + dy * (screenH * 0.5));
    }

    /** Linger long at low score, short when severe. */
    private long fleeLingerForScore(int score) {
        double t = clamp01((score - AMBIENT_SCORE) / (double) (SCORE_MAX - AMBIENT_SCORE));
        return (long) (FLEE_LINGER_MAX_MS - t * (FLEE_LINGER_MAX_MS - FLEE_LINGER_MIN_MS));
    }

    /** Shared core of swipe + shake: kick every bee outward and hold the flee state for a while. */
    private void flee(double dirX, double dirY, boolean radial, int score) {
        long now = System.currentTimeMillis();
        fleeRadial = radial;
        fleeDirX = dirX;
        fleeDirY = dirY;
        fleeEndTime = now + fleeLingerForScore(score);

        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;
        synchronized (beesLock) {
            for (SingleBee bee : bees) {
                double odx, ody;
                if (radial) {
                    odx = bee.getPosX() - centerX;
                    ody = bee.getPosY() - centerY;
                    double len = Math.sqrt(odx * odx + ody * ody);
                    if (len < 1) {
                        double a = random.nextDouble() * Math.PI * 2;
                        odx = Math.cos(a);
                        ody = Math.sin(a);
                    } else {
                        odx /= len;
                        ody /= len;
                    }
                } else {
                    odx = dirX;
                    ody = dirY;
                }
                bee.applyImpulse(odx * SWIPE_IMPULSE, ody * SWIPE_IMPULSE);
            }
        }
        if (score >= NEST_SCORE) {
            // Stirred-up nest: they come back fast and angry (short linger + angriness boost).
            nestBoostEndTime = now + NEST_BOOST_MS;
        }
    }

    private void onShake() {
        ProductivityState state = ProductivityEngine.getInstance().getState().getValue();
        int score = state != null ? state.getScore() : 0;
        if (score <= AMBIENT_SCORE) return; // no bees to scatter at this stage
        flee(0, 0, true, score);
        Log.d(TAG, "Shake scattered swarm (score=" + score + ")");
    }

    // --- Swipe-to-disperse: a full-screen touch layer that is only present at high severity. ---
    @SuppressLint("ClickableViewAccessibility")
    private void setSwipeLayer(boolean show) {
        mainHandler.post(() -> {
            if (show && swipeLayer == null) {
                if (!Settings.canDrawOverlays(context)) return;

                View layer = new View(context);
                GestureDetector detector = new GestureDetector(context,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                                onSwipe(velocityX, velocityY);
                                return true;
                            }
                        });
                layer.setOnTouchListener((v, event) -> detector.onTouchEvent(event));

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                try {
                    windowManager.addView(layer, params);
                    swipeLayer = layer;
                } catch (Exception e) {
                    Log.e(TAG, "Error adding swipe layer", e);
                }
            } else if (!show && swipeLayer != null) {
                try {
                    windowManager.removeView(swipeLayer);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing swipe layer", e);
                }
                swipeLayer = null;
            }
        });
    }

    private void onSwipe(float velocityX, float velocityY) {
        double len = Math.sqrt((double) velocityX * velocityX + (double) velocityY * velocityY);
        if (len < 1) return;
        double nx = velocityX / len;
        double ny = velocityY / len;

        ProductivityState state = ProductivityEngine.getInstance().getState().getValue();
        int score = state != null ? state.getScore() : 0;
        // Same flee path as shake: push bees to the edge and hold them there (no despawn churn).
        flee(nx, ny, false, score);
        Log.d(TAG, "Swipe dispersed swarm (score=" + score + ")");
    }

    /** Hard teardown (service destroy / level 0). Removes every overlay window immediately. */
    public void removeAllBees() {
        isSimulationRunning = false;
        swipeLayerDesired = false; // keep the desired-state flag in sync with the torn-down layer
        if (shakeDetector != null) shakeDetector.stop();
        fleeEndTime = 0;
        lastHoneyBand = 0;
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(honeyHideRunnable);
            removeHoneyBottleView();
            for (View view : beeViews.values()) {
                try {
                    windowManager.removeView(view);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing bee view", e);
                }
            }
            beeViews.clear();
            if (swipeLayer != null) {
                try {
                    windowManager.removeView(swipeLayer);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing swipe layer", e);
                }
                swipeLayer = null;
            }
        });
        synchronized (beesLock) {
            bees.clear();
            despawning.clear();
        }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static double frac(double v) {
        double f = v - Math.floor(v);
        return f;
    }

    /** Maps a 0..1 value into the 0.05..0.95 band so cover goals stay on-screen. */
    private static double mapUnit(double u) {
        return 0.05 + 0.90 * u;
    }
}
