//Cloned by AI (from com.example.screentests.services.BeeManager, reworked for the standalone
//swarm showcase). The productivity-score plumbing was removed: swarm size and angriness are set
//directly (sliders in SwarmShowcaseActivity). Bees render as ImageViews inside a plain ViewGroup
//instead of system-overlay windows, so NO overlay permission is needed. The swipe-and-disperse
//layer and the shake detector are PERMANENTLY active, and the bees fly the same radial
//(orbit-ring) goal pattern as the original.

package com.example.swarmshowcase;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Owns the showcase bee swarm. Same boids simulation as the BeeProductive original
 * ({@link SingleBee} is byte-for-byte the same physics), driven by two external knobs:
 *
 *   setSwarmSize(int)     — how many bees fly (slider)
 *   setAngriness(double)  — 0..1 aggression: speed, jitter, personal space (slider)
 *
 * Interactions (always on):
 *   - swipe anywhere over the swarm: bees are blown along the swipe and linger at the edge
 *   - shake the device: bees scatter RADIALLY from the screen centre (same radial goal pattern)
 */
public class BeeManager {
    public enum dim {WIDTH, HEIGHT}

    private static final String TAG = "ShowcaseBeeManager";

    //Behaviour tuning (same knobs as the original)
    private static final double ORBIT_RADIUS_FACTOR = 0.45; // ring radius vs half-screen; smaller than the original 0.75 so the showcase swarm stays visible over the honey
    private static final double ORBIT_SPEED = 0.02;         //radians per frame -> frame rate dependant (not good but whatever)
    private static final double SLOSH_INNER_FACTOR = 0.35;  //how far inside the screen a sloshing bee dips
    private static final double OFFSCREEN_GOAL_FACTOR = 1.5; //despawn goal distance vs screen size
    private static final double SWIPE_IMPULSE = 45.0;       //for bee goal vector calc
    private static final long NEST_BOOST_MS = 2500;         //stirring up an already angry nest

    //Edge fade: a bee becomes fully transparent within this fraction of the smaller screen side
    private static final double EDGE_FADE_FRACTION = 1;
    //Shake/swipe flee: bees rush to the edge and linger there. Linger is long when the swarm is
    //calm, short when it is angry (the original scaled this with the unproductivity score).
    private static final long FLEE_LINGER_MAX_MS = 5000;
    private static final long FLEE_LINGER_MIN_MS = 1000;

    private static final int FRAME_MS = 32; // etw 30 FPS
    private static final int BEE_VIEW_SIZE = 100; // px, matches the original overlay views

    private final Context context;
    /** The showcase renders into this container instead of system-overlay windows. */
    private final ViewGroup container;
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

    //the two showcase knobs (replacing the productivity score)
    private volatile int targetSwarmSize = 12;
    private volatile double angriness = 0.2;

    //cached at simulation start so the background loop doesnt touch the view for metrics
    private int screenW = 1;
    private int screenH = 1;

    private long frame = 0;

    //permanent swipe layer (only touched on the main thread)
    private View swipeLayer = null;
    // Written from the swipe callback (main thread), read by the sim loop -> volatile for safe publication.
    private volatile long nestBoostEndTime = 0;

    //for user interaction states (overwritten by gesture callbacks)
    private volatile long fleeEndTime = 0;
    private volatile boolean fleeRadial = false; // true: scatter radially (shake); false: along fleeDir (swipe)
    private volatile double fleeDirX = 0;
    private volatile double fleeDirY = 0;
    private final ShakeDetector shakeDetector;

    public BeeManager(Context context, ViewGroup container) {
        this.context = context;
        this.container = container;
        this.shakeDetector = new ShakeDetector(context, this::onShake);
    }

    /** How many bees should fly. Applied gradually (spawn/despawn) by the sim loop. */
    public void setSwarmSize(int count) {
        this.targetSwarmSize = Math.max(0, count);
    }

    /** 0..1 — how angry the swarm is: speed, jitter, personal space, goal chase. */
    public void setAngriness(double value) {
        this.angriness = clamp01(value);
    }

    public int getWindowSize(dim dimension) {
        return dimension == dim.WIDTH ? container.getWidth() : container.getHeight();
    }

    /** Starts the bee simulation. Call once the container is laid out (e.g. container.post(...)). */
    public void startSimulation() {
        if (isSimulationRunning) return;
        isSimulationRunning = true;

        //Snapshot the container size once on the main thread.
        screenW = Math.max(1, getWindowSize(dim.WIDTH));
        screenH = Math.max(1, getWindowSize(dim.HEIGHT));

        //permanently armed interaction handlers
        shakeDetector.start();
        setSwipeLayer(true);

        new Thread(() -> {
            Log.d(TAG, "Simulation started");
            while (isSimulationRunning) {
                driveSwarm();

                // Physics on a stable snapshot, then ONE batched UI post for the whole frame
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

                killOffScreenBees(snapshot);

                frame++;
                try {
                    Thread.sleep(FRAME_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            isSimulationRunning = false;
            shakeDetector.stop();
            Log.d(TAG, "Simulation stopped");
        }).start();
    }

    /**
     * Reads the two knobs and updates target count, goals and per-bee angriness — the same
     * radial orbit-ring goal pattern as the original, minus the score-driven special events.
     */
    private void driveSwarm() {
        long now = System.currentTimeMillis();

        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;
        double ringX = screenW * ORBIT_RADIUS_FACTOR;
        double ringY = screenH * ORBIT_RADIUS_FACTOR;

        boolean nestBoost = now < nestBoostEndTime;
        double effectiveAngriness = nestBoost ? Math.max(angriness, 0.9) : angriness;
        //angrier swarms slosh deeper into the screen, like the high-score swarm did
        double slosh = effectiveAngriness;

        boolean structureChanged = adjustPopulation(targetSwarmSize, centerX, centerY, ringX, ringY, nestBoost);

        //goal + angriness update on a snapshot so the lock isn't held during the math
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
            if (fleeing) {//upon shake/swipe
                setFleeGoal(bee, centerX, centerY);
                bee.setAngriness(effectiveAngriness);
                continue;
            }

            //Radial circular goal movement (-> usual case, same pattern as the original)
            double angle = bee.getPhase() + frame * ORBIT_SPEED * (1 + effectiveAngriness);
            double radiusFactor = 1.0;
            //bee sloshing. Doing it for one bee is usually enough because of the swarm cohesion force
            double wave = Math.sin(frame * 0.012 + bee.getPhase() * 3.0);
            if (slosh > 0 && wave > (1.0 - slosh)) {
                radiusFactor = SLOSH_INNER_FACTOR;
            }
            double gx = centerX + Math.cos(angle) * ringX * radiusFactor;
            double gy = centerY + Math.sin(angle) * ringY * radiusFactor;
            bee.setGoal(gx, gy);
            bee.setAngriness(effectiveAngriness);
        }

        if (structureChanged) {//list refresh -> update neighbours
            refreshNeighbourSnapshots();
        }
    }

    /**
     * Spawns bees if needed. Bees to despawn are only flagged here -> killOffScreenBees() does the rest
     * @return true if the population changed
     */
    private boolean adjustPopulation(int targetCount, double centerX, double centerY, double ringX, double ringY, boolean nestBoost) {
        boolean changed = false;
        int spawnPerFrame = nestBoost ? 4 : 1; //bees reappear faster if you stirred up the nest

        synchronized (beesLock) {
            int active = bees.size() - despawning.size();

            if (active < targetCount) {//MORE BEES
                int toSpawn = Math.min(spawnPerFrame, targetCount - active);
                for (int i = 0; i < toSpawn; i++) {
                    SingleBee bee = new SingleBee(screenW, screenH, maxVisualOverhead);
                    // Spawn on the ring so the bee flies in from the edge
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
                        despawning.add(bee); // gets an off-screen goal and fades at the edge
                        toRemove--;
                    }
                }
            }
        }
        return changed;
    }

    /** Removes the icons of despawning bees that have now left the screen. */
    private void killOffScreenBees(List<SingleBee> snapshot) {
        List<SingleBee> killed = null;
        synchronized (beesLock) {
            for (SingleBee bee : snapshot) {
                if (despawning.contains(bee) && bee.isOffScreen()) {
                    if (killed == null) killed = new ArrayList<>();
                    killed.add(bee);
                }
            }
            if (killed != null) {
                bees.removeAll(killed);
                despawning.removeAll(killed);
            }
        }
        if (killed != null) {
            final List<SingleBee> toRemove = killed;
            mainHandler.post(() -> {
                for (SingleBee bee : toRemove) {
                    View view = beeViews.remove(bee);
                    if (view != null) {
                        container.removeView(view);
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
        if (len < 1) {//to stop bees from getting stuck right in the center
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
     * fully transparent as the bee touches the edge. Uses the bee view centre.
     */
    private float clampedEdgeAlpha(int x, int y) {
        double cx = x + BEE_VIEW_SIZE / 2.0;
        double cy = y + BEE_VIEW_SIZE / 2.0;
        double margin = EDGE_FADE_FRACTION * Math.min(screenW, screenH);
        if (margin < 1) margin = 1;
        double minEdge = Math.min(Math.min(cx, cy), Math.min(screenW - cx, screenH - cy));
        double a = minEdge / margin;
        if (a < 0) a = 0;
        if (a > 1) a = 1;
        return (float) a;
    }

    /**
     * ONE batched main-thread update for the whole frame. Modified for the showcase: bee views are
     * children of the container (moved via translation) instead of WindowManager overlay windows.
     */
    private void renderBeeFrames(List<BeeFrame> frames) {
        mainHandler.post(() -> {
            if (!isSimulationRunning) return; // torn down between post and run

            for (BeeFrame f : frames) {
                View view = beeViews.get(f.bee);
                if (view == null) {
                    ImageView imageView = new ImageView(context);
                    imageView.setImageResource(R.drawable.worker_bee);
                    imageView.setAlpha(f.alpha);
                    imageView.setTranslationX(f.x);
                    imageView.setTranslationY(f.y);

                    FrameLayout.LayoutParams params =
                            new FrameLayout.LayoutParams(BEE_VIEW_SIZE, BEE_VIEW_SIZE);
                    // Insert below the swipe layer so the layer keeps receiving gestures.
                    int index = swipeLayer != null ? container.indexOfChild(swipeLayer) : -1;
                    container.addView(imageView, index >= 0 ? index : -1, params);
                    beeViews.put(f.bee, imageView);
                } else {
                    view.setTranslationX(f.x);
                    view.setTranslationY(f.y);
                    if (view.getAlpha() != f.alpha) {
                        view.setAlpha(f.alpha);
                    }
                }
            }
        });
    }

    /** per frame record. Built on the sim thread. */
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

    private void setFleeGoal(SingleBee bee, double centerX, double centerY) {//fleeing works better than chasing because bees should go off screen
        double dx, dy;
        if (fleeRadial) {
            dx = bee.getPosition(dim.WIDTH) - centerX;
            dy = bee.getPosition(dim.HEIGHT) - centerY;
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

    /** Linger time after a flee. The original scaled with score; the showcase uses angriness. */
    private long fleeLingerForAngriness(double a) {
        double t = clamp01(a);
        return (long) (FLEE_LINGER_MAX_MS - t * (FLEE_LINGER_MAX_MS - FLEE_LINGER_MIN_MS));//longer when calm
    }

    /** Shared core of swipe + shake: kick every bee outward and hold the flee state for a while. */
    private void flee(double dirX, double dirY, boolean radial) {
        long now = System.currentTimeMillis();
        fleeRadial = radial;
        fleeDirX = dirX;
        fleeDirY = dirY;
        fleeEndTime = now + fleeLingerForAngriness(angriness);

        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;
        synchronized (beesLock) {
            for (SingleBee bee : bees) {
                double odx, ody;
                if (radial) {
                    odx = bee.getPosition(dim.WIDTH) - centerX;
                    ody = bee.getPosition(dim.HEIGHT) - centerY;
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
                bee.applyDelta_v(odx * SWIPE_IMPULSE, ody * SWIPE_IMPULSE);
            }
        }
        if (angriness >= 0.85) {
            //angriness boosted when trying to shake away already angry bees (stirred-up nest)
            nestBoostEndTime = now + NEST_BOOST_MS;
        }
    }

    private void onShake() {
        flee(0, 0, true);
        Log.d(TAG, "Shake scattered swarm (radial)");
    }

    /**
     * Swipe overlay that lets the user disperse the swarm directly. Permanently installed for the
     * showcase (the original only armed it above a score threshold).
     * @param show so we can also tear the layer down in removeAllBees()
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setSwipeLayer(boolean show) {
        mainHandler.post(() -> {
            if (show && swipeLayer == null) {
                View layer = new View(context);
                GestureDetector detector = new GestureDetector(context,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true; //claim the gesture so onFling can fire
                            }

                            @Override
                            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                                onSwipe(velocityX, velocityY);
                                return true;
                            }
                        });
                layer.setOnTouchListener((v, event) -> detector.onTouchEvent(event));

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                container.addView(layer, params);
                swipeLayer = layer;
            } else if (!show && swipeLayer != null) {
                container.removeView(swipeLayer);
                swipeLayer = null;
            }
        });
    }

    private void onSwipe(float velocityX, float velocityY) {
        double len = Math.sqrt((double) velocityX * velocityX + (double) velocityY * velocityY);
        if (len < 1) return;
        double nx = velocityX / len;
        double ny = velocityY / len;

        //same as shake but along the swipe direction
        flee(nx, ny, false);
        Log.d(TAG, "Swipe dispersed swarm");
    }

    /**This method hard shutdowns everything. Writing it was a good idea*/
    public void removeAllBees() {
        isSimulationRunning = false;
        shakeDetector.stop();
        fleeEndTime = 0;
        mainHandler.post(() -> {
            for (View view : beeViews.values()) {
                container.removeView(view);
            }
            beeViews.clear();
        });
        setSwipeLayer(false);
        synchronized (beesLock) {
            bees.clear();
            despawning.clear();
        }
    }

    //Math helpers
    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
