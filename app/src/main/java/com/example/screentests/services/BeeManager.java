package com.example.screentests.services;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
 * OverlayService still drives us only through initBeeSwarm/startSimulation/removeAllBees.
 */
public class BeeManager {
    public enum dim {WIDTH, HEIGHT}

    private static final String TAG = "BeeManager";

    // --- Score thresholds (all swarm display keys off these) ---
    private static final int SCORE_MAX = 100;
    private static final int AMBIENT_SCORE = 20;   // at/below this: no bees (matches engine level 0)
    private static final int SLOSH_START = 35;     // above this: bees start dipping into view
    private static final int ANGER_START = 40;     // above this: angriness ramps up
    private static final int SWIPE_LAYER_SCORE = 70; // at/above this: swipe-to-disperse layer is live
    private static final int NEST_SCORE = 85;      // at/above this: swipes stir up the "nest"
    private static final int SWARM_EVENT_SCORE = 90; // brief all-in swarm event (below max)

    private static final int MAX_BEES = 24;

    // --- Behaviour tuning ---
    private static final double ORBIT_RADIUS_FACTOR = 0.75; // ring radius vs half-screen -> mostly off-screen
    private static final double ORBIT_SPEED = 0.02;         // radians per frame
    private static final double SLOSH_INNER_FACTOR = 0.35;  // how far inside the screen a sloshing bee dips
    private static final double OFFSCREEN_GOAL_FACTOR = 1.5; // despawn goal distance vs screen size
    private static final long SWARM_EVENT_DURATION_MS = 1800;
    private static final long INTERVENTION_COVER_MS = 1600;
    private static final long NEST_BOOST_MS = 2500;
    private static final double SWIPE_IMPULSE = 45.0;

    private static final int FRAME_MS = 32; // ~30 FPS

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

    private volatile boolean isSimulationRunning = false;

    // Cached at simulation start so the background loop never touches the WindowManager for metrics.
    private int screenW = 1;
    private int screenH = 1;

    private long frame = 0;

    // Brief "all bees swarm the screen" event (last escalation step before max).
    private boolean swarmEventArmed = true;
    private boolean swarmEventActive = false;
    private long swarmEventEndTime = 0;

    // Intervention: cover the screen, then vanish entirely while the flag holds.
    private boolean interventionVanishing = false;
    private long interventionCoverEndTime = 0;
    private boolean lastIntervention = false;

    // Swipe-to-disperse touch layer (only present at high severity).
    private View swipeLayer = null;
    private boolean swipeLayerDesired = false; // sim-thread view of whether the layer should exist
    // Written from the swipe callback (main thread), read by the sim loop -> volatile for safe publication.
    private volatile long nestBoostEndTime = 0;

    public BeeManager(Context context, WindowManager windowManager, int score) {
        this.context = context;
        this.windowManager = windowManager;
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

        new Thread(() -> {
            Log.d(TAG, "Simulation started");
            while (isSimulationRunning) {
                ProductivityState state = ProductivityEngine.getInstance().getState().getValue();
                int score = state != null ? state.getScore() : 0;
                boolean intervention = state != null && state.isShowInterventionOverlay();

                driveSwarm(score, intervention);

                // Physics + render on a stable snapshot; each bee uses its own neighbour copy.
                List<SingleBee> snapshot;
                synchronized (beesLock) {
                    snapshot = new ArrayList<>(bees);
                }
                for (SingleBee bee : snapshot) {
                    bee.timeStep();
                    updateBeeVisuals(bee);
                }

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
        int targetCount;
        if (intervention && interventionVanishing) {
            targetCount = 0; // vanish phase
        } else if (coverScreen) {
            targetCount = MAX_BEES; // fill the screen
        } else if (score <= AMBIENT_SCORE) {
            targetCount = 0;
        } else {
            double t = (score - AMBIENT_SCORE) / (double) (SCORE_MAX - AMBIENT_SCORE);
            targetCount = (int) Math.round(MAX_BEES * t);
        }
        targetCount = Math.max(0, Math.min(MAX_BEES, targetCount));

        boolean structureChanged = adjustPopulation(targetCount, centerX, centerY, ringX, ringY, nestBoost);

        // --- Assign goals + angriness for this frame ---
        List<SingleBee> snapshot;
        synchronized (beesLock) {
            snapshot = new ArrayList<>(bees);
        }
        for (SingleBee bee : snapshot) {
            boolean isDespawning;
            synchronized (beesLock) {
                isDespawning = despawning.contains(bee);
            }
            if (isDespawning) {
                setOffScreenGoal(bee, centerX, centerY, ringX, ringY);
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
    }

    /**
     * Spawns toward / despawns toward the target count. Despawning bees are only marked here; they
     * are not removed until they leave the screen (handled by reapOffScreenBees).
     * Returns true if the bee list membership changed.
     */
    private boolean adjustPopulation(int targetCount, double centerX, double centerY,
                                     double ringX, double ringY, boolean nestBoost) {
        boolean changed = false;
        int spawnPerFrame = nestBoost ? 4 : 1; // nest stir-ups regather fast

        synchronized (beesLock) {
            int active = bees.size() - despawning.size();

            if (active < targetCount) {
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
        if (len < 1) {
            double a = random.nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dy = Math.sin(a);
            len = 1;
        }
        double gx = centerX + (dx / len) * ringX * OFFSCREEN_GOAL_FACTOR / ORBIT_RADIUS_FACTOR;
        double gy = centerY + (dy / len) * ringY * OFFSCREEN_GOAL_FACTOR / ORBIT_RADIUS_FACTOR;
        bee.setGoal(gx, gy);
    }

    private void updateBeeVisuals(SingleBee bee) {
        mainHandler.post(() -> {
            // Don't recreate a view for a bee that has already been reaped.
            synchronized (beesLock) {
                if (!bees.contains(bee)) return;
            }
            View view = beeViews.get(bee);
            int x = bee.getPosition(dim.WIDTH);
            int y = bee.getPosition(dim.HEIGHT);

            if (view == null) {
                ImageView imageView = new ImageView(context);
                imageView.setImageResource(R.mipmap.ic_launcher_round);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        100, 100,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = x;
                params.y = y;

                try {
                    windowManager.addView(imageView, params);
                    beeViews.put(bee, imageView);
                } catch (Exception e) {
                    Log.e(TAG, "Error adding bee view", e);
                }
            } else {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
                params.x = x;
                params.y = y;
                try {
                    windowManager.updateViewLayout(view, params);
                } catch (Exception e) {
                    Log.e(TAG, "Error updating bee layout", e);
                }
            }
        });
    }

    // --- Swipe-to-disperse: a full-screen touch layer that is only present at high severity. ---
    @SuppressLint("ClickableViewAccessibility")
    private void setSwipeLayer(boolean show) {
        mainHandler.post(() -> {
            if (show && swipeLayer == null) {
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
        boolean nest = score >= NEST_SCORE;

        synchronized (beesLock) {
            for (SingleBee bee : bees) {
                bee.applyImpulse(nx * SWIPE_IMPULSE, ny * SWIPE_IMPULSE);
                despawning.add(bee); // scatter off-screen; reaped once they leave the screen
            }
        }
        if (nest) {
            // Stirred-up nest: they come back fast and angry.
            nestBoostEndTime = System.currentTimeMillis() + NEST_BOOST_MS;
        }
        Log.d(TAG, "Swipe dispersed swarm (nest=" + nest + ")");
    }

    /** Hard teardown (service destroy / level 0). Removes every overlay window immediately. */
    public void removeAllBees() {
        isSimulationRunning = false;
        swipeLayerDesired = false; // keep the desired-state flag in sync with the torn-down layer
        mainHandler.post(() -> {
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
