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
 * directly from {@link ProductivityEngine}. more unproductivity -> more bees, angrier bees,
 * bees slosh in from off-screen, a brief screen-swarm near max score, and a cover-then-vanish
 * during the intervention. Swarm logic exclusive to this and {@link SingleBee} for bee instances
 *
 * I let AI pass over this class and unify / clean up the codebase. I have marked AI generated methods and
 * let the AI mark where it merged duplicate elements across my classes with "Outsourced duplicate codebase by AI".
 */
public class BeeManager {
    //Variables mostly extracted from the code by AI
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

    private static final int FRAME_MS = 32; // etw 30 FPS

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
     * Kept for the OverlayManager contract. Bee count is now derived from the score, so this
     * just ensures the score-driven simulation is running (-> no destructive repopulate -> race cond.!)
     */
    public void initBeeSwarm(int level) {
        Log.d(TAG, "initBeeSwarm hint level: " + level);
        startSimulation();
    }

    /** Starts the bee simulation
     * **/
    public void startSimulation() {
        if (isSimulationRunning) return;
        isSimulationRunning = true;

        //Snapshot screen size once on the main thread.
        //If we wanted the screen to be able to rotate then this would need constant refreshing!
        screenW = Math.max(1, getWindowSize(dim.WIDTH));
        screenH = Math.max(1, getWindowSize(dim.HEIGHT));

        //shake listener
        if (shakeDetector != null) shakeDetector.start();

        new Thread(() -> {
            Log.d(TAG, "Simulation started");
            while (isSimulationRunning) {
                ProductivityState state = ProductivityEngine.getInstance().getState().getValue();
                int score = state != null ? state.getScore() : 0;
                boolean intervention = (state != null && state.isShowInterventionOverlay());

                driveSwarm(score, intervention);

                // Physics on a stable snapshot, then ONE batched UI post for the whole frame - fix by AI
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

                //stop condition, OverlayManager restarts if necessary
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
     * reads score and updates target count, goals, angriness, special events (only 90% swarm event atm)
     */
    private void driveSwarm(int score, boolean intervention) {
        long now = System.currentTimeMillis();

        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;
        double ringX = screenW * ORBIT_RADIUS_FACTOR;
        double ringY = screenH * ORBIT_RADIUS_FACTOR;

        double angriness = clamp01((score - ANGER_START) / (double) (SCORE_MAX - ANGER_START));
        double slosh = clamp01((score - SLOSH_START) / (double) (SCORE_MAX - SLOSH_START));

        //Swarm states
        if (intervention && !lastIntervention) {
            //start cover phase
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

        //90% swarm evebt
        if (!intervention) {
            if (score >= SWARM_EVENT_SCORE) {
                if (swarmEventArmed && !swarmEventActive) {
                    swarmEventActive = true;
                    swarmEventEndTime = now + SWARM_EVENT_DURATION_MS;
                    swarmEventArmed = false;
                    Log.d(TAG, "Swarm event triggered");
                }
            } else if (score < SWARM_EVENT_SCORE - 5) {
                swarmEventArmed = true; //i like to think of this as the reload speed of a swarm "cannon" lol
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

        //Population
        int maxBees = ProductivityEngine.getInstance().getMaxBees();
        int targetCount;
        if (intervention && interventionVanishing) {
            targetCount = 0; //vanish
        } else if (coverScreen) {
            targetCount = maxBees; //fill screen
        } else if (score <= AMBIENT_SCORE) {
            targetCount = 0;
        } else {
            double t = (score - AMBIENT_SCORE) / (double) (SCORE_MAX - AMBIENT_SCORE);
            targetCount = (int) Math.round(maxBees * t);
        }
        targetCount = Math.max(0, Math.min(maxBees, targetCount));

        boolean structureChanged = adjustPopulation(targetCount, centerX, centerY, ringX, ringY, nestBoost);

        //goal + angriness update
        //bee despawning needs to stay under one lock -> if not then lots of lag :/
        //snapshot is used to iterate over a single list of bees without holding onto the lock during logic/math
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
                setFleeGoal(bee, centerX, centerY);//push force origin basically -> works better than random edge pos because screen isnt a circle (hopefully)
                bee.setAngriness(effectiveAngriness);
                continue;
            }

            double gx, gy;
            if (coverScreen) {//AI generated screen cover event. I don't really understand the maths here but it seems to work
                gx = screenW * mapUnit(frac(bee.getPhase()));
                gy = screenH * mapUnit(frac(bee.getPhase() * 1.6180339887));
            } else {//Random circular goal movement (-> usual case)
                double angle = bee.getPhase() + frame * ORBIT_SPEED * (1 + effectiveAngriness);
                double radiusFactor = 1.0;
                //bee sloshing. Doing it for one bee is usually enough because of the swarm cohesion force
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

        if (structureChanged) {//list refresh -> update neighbours
            refreshNeighbourSnapshots();
        }

        //add swipe layer on high unproductivity severity
        //TODO: Make this layer dynamic and make an own handler so it only registers bee swipes
        boolean wantSwipeLayer = score >= SWIPE_LAYER_SCORE;
        if (wantSwipeLayer != swipeLayerDesired) {
            swipeLayerDesired = wantSwipeLayer;
            setSwipeLayer(wantSwipeLayer);
        }

        //I think i like outsourcing too much
        updateHoneyIndicator(score);
    }

    /**
     * Spawns bees if needed. Bees to despawn are only flagged here -> reapOffScreenBees() does the rest
     * @return true if the population changed
     */
    private boolean adjustPopulation(int targetCount, double centerX, double centerY, double ringX, double ringY, boolean nestBoost) {
        boolean changed = false;
        int spawnPerFrame = nestBoost ? 4 : 1; //bees reappear faster if you stirred up the nest. Not sure if this is actually doing much though

        //synchronize both lists under the lock because otherwise lint wont stop screaming at me for some reason
        synchronized (beesLock) {
            int active = bees.size() - despawning.size();

            if (active < targetCount) {//MORE BEES
                int toSpawn = Math.min(spawnPerFrame, targetCount - active);
                for (int i = 0; i < toSpawn; i++) {
                    SingleBee bee = new SingleBee(screenW, screenH, maxVisualOverhead);
                    // Spawn off-screen on the ring so the bee flies in from the edge ->
                    //didnt end up working due to canvas issues but at least bees appear at the edges consistently
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
                        despawning.add(bee); // gets an off-screen goal, again the bees can no longer fly off screen
                        // but the transparacy fixes it part way at least
                        toRemove--;
                    }
                }
            }
        }
        return changed;
    }

    /** Removes the icons of despawning bees that have now left the screen.
     * */
    private void killOffScreenBees(List<SingleBee> snapshot) { //"kill" feels a little brutal, rename if you prefer @Samuel
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
                        try {
                            windowManager.removeView(view);
                        } catch (Exception e) {
                            Log.e(TAG, "Error removing killed bee view", e);//i pray this never matters
                        }
                    }
                }
            });
            refreshNeighbourSnapshots();
        }
    }

    private void refreshNeighbourSnapshots() {//Outsourced duplicate codebase by AI
        synchronized (beesLock) {
            List<SingleBee> copy = new ArrayList<>(bees);
            for (SingleBee bee : bees) {
                bee.updateExistingBees(copy);
            }
        }
    }

    private void setOffScreenGoal(SingleBee bee, double centerX, double centerY, double ringX, double ringY) {//Outsourced duplicate codebase by AI
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
     * fully transparent as the bee touches the edge. Uses the bee view centre (views are 100px).
     * AI-Generated.
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
        // AI-changed: with zero bees there is nothing to draw — skip the post instead of waking
        // the main thread every 32ms with an empty runnable while the sim idles/drains.
        if (frames.isEmpty()) return;
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

    /** per frame recordm. Built on the sim thread. */
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
        }//Ive never written a class this java, ever.
    }


    private void updateHoneyIndicator(int score) {
        if (score <= AMBIENT_SCORE) {
            lastHoneyBand = 0;
            return;
        }
        int band = (score < SLOSH_START) ? 1 : (score < ANGER_START) ? 2 : 3;//1 full, 3 empty

        if (band > lastHoneyBand) {
            showHoneyBottle(band);
        }
        lastHoneyBand = band;
    }

    //Update Honey bottle visuals, partially AI generated
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
    };//Outsourced duplicate codebase by AI


    private void removeHoneyBottleView() {//Outsourced duplicate codebase by AI
        if (honeyBottleView != null) {
            try {
                windowManager.removeView(honeyBottleView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing honey bottle", e);
            }
            honeyBottleView = null;
        }
    }


    private void setFleeGoal(SingleBee bee, double centerX, double centerY) {//fleeing works better than chasing because bees should go off screen
        //roughly equally spread and this was a nightmare with just the goal set mechanic
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


    private long fleeLingerForScore(int score) {
        double t = clamp01((score - AMBIENT_SCORE) / (double) (SCORE_MAX - AMBIENT_SCORE));
        return (long) (FLEE_LINGER_MAX_MS - t * (FLEE_LINGER_MAX_MS - FLEE_LINGER_MIN_MS));//longer at low score
    }

    /** Shared core of swipe + shake: kick every bee outward and hold the flee state for a while. Outsourced duplicate codebase by AI*/
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
        if (score >= NEST_SCORE) {
            //angriness boosted when trying to shake away even slightly angry bees
            nestBoostEndTime = now + NEST_BOOST_MS;
        }
    }

    private void onShake() {//Outsourced duplicate codebase by AI
        ProductivityState state = ProductivityEngine.getInstance().getState().getValue();
        int score = state != null ? state.getScore() : 0;
        if (score <= AMBIENT_SCORE) return; // no bees to scatter at this stage
        flee(0, 0, true, score);
        Log.d(TAG, "Shake scattered swarm (score=" + score + ")");
    }


    /** Swipe overlay for high severities that allows the user to interact with the swarm directly.
     * this whole thing was supposed to be an intermediary solution until we get actual
     * onTouchListeners implemented but as it turns out im too dumb to do that- so this will stay
      * @param show so we can also initalize the layer without it being visible
     */
    @SuppressLint("ClickableViewAccessibility")//whatever lint was on about here
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
                        });//Made with Help of AI
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

    private void onSwipe(float velocityX, float velocityY) {//Outsourced for readability
        double len = Math.sqrt((double) velocityX * velocityX + (double) velocityY * velocityY);
        if (len < 1) return;
        double nx = velocityX / len;
        double ny = velocityY / len;

        ProductivityState state = ProductivityEngine.getInstance().getState().getValue();
        int score = state != null ? state.getScore() : 0;
        //same as shake but dont kill bees
        flee(nx, ny, false, score);
        Log.d(TAG, "Swipe dispersed swarm (score=" + score + ")");
    }

    /**This method hard shutdowns everything. Writing it was a good idea*/
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

    //Math helpers
    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static double frac(double v) {
        double f = v - Math.floor(v);
        return f;
    }
    
    //0...1      to      0.05...0.95
    private static double mapUnit(double u) {
        return 0.05 + 0.90 * u;
    }
}
