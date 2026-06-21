# Bee Swarm Smart-UI — Change Summary

This document explains the rework of the bee overlay swarm. **Only two files changed:**

- `app/src/main/java/com/example/screentests/services/SingleBee.java` — a single bee's physics.
- `app/src/main/java/com/example/screentests/services/BeeManager.java` — the swarm brain.

`OverlayService`, `ProductivityEngine`, and everything else are **untouched**. The contract
between OverlayService and BeeManager is unchanged: OverlayService still only ever calls
`initBeeSwarm(int)`, `startSimulation()`, and `removeAllBees()`.

---

## 1. Why the old swarm was broken

`OverlayService.updateBees()` → `initBeeSwarm(level)` → `removeAllBees()`. The old
`removeAllBees()` **posted `bees.clear()` to the main handler**, but `initBeeSwarm()` then
re-filled `bees` synchronously on that same main thread. The posted `clear()` ran *afterwards*
and wiped the freshly created bees, so the swarm never reliably appeared. The background sim
thread also iterated `bees` while that posted `clear()` fired (`ConcurrentModificationException`),
and `isSimulationRunning` was not `volatile`.

**Fix:** `bees` is now the single source of truth, mutated only under `beesLock`. Only *view
removals* are posted to the main thread. `initBeeSwarm()` no longer destroys/repopulates (count
is score-driven now), which removes the race entirely. `isSimulationRunning` and `nestBoostEndTime`
are `volatile`.

---

## 2. Where the score comes from

BeeManager reads the live productivity score directly from the existing singleton, once per frame:

```java
ProductivityState state = ProductivityEngine.getInstance().getState().getValue();
int score = state.getScore();              // 0..100, updated each minute by the engine
boolean intervention = state.isShowInterventionOverlay(); // true when score hit max
```

This is a **read-only pull** — no new messaging, no change to how classes communicate.
`currentScore` is populated by `ProductivityEngine.tickScoreLogic()` and published via
`postStateUpdate()`; we verified it is live before relying on it.

**Everything visual is keyed off `score`** (count, angriness, sloshing, the swarm event, the
swipe layer, the intervention).

---

## 3. SingleBee.java — what's new

A bee keeps its boids behaviour (separation / alignment / cohesion) and gains:

| Member | Purpose |
| --- | --- |
| `setGoal(x, y)` + `calculateGoalForce()` | **The "set goal" mechanic.** A 4th force that steers the bee toward a target, same additive form as cohesion. Everything macro (orbiting, sloshing, swarming, despawning) is expressed by moving this goal. |
| `setAngriness(0..1)` | **Local** aggression. In `timeStep()` it raises the speed cap, adds chaotic jitter, and amplifies separation/goal pull. It layers on top of the swarm forces — it never replaces them. |
| `phase` (+ `getPhase()`) | Stable random orbit identity so BeeManager can keep each bee on its own slice of the ring without a parallel map. |
| `setPosition(x, y)` | Lets BeeManager spawn a bee off-screen on the ring so it flies in from the edge. |
| `applyImpulse(dx, dy)` | A velocity kick, used when a swipe pushes the swarm. |
| `isOffScreen()` | True when the bee is outside the visible screen rect — despawning is only allowed here. |

Other cleanups:
- The three boids forces now share **one** `getClosestNeighbors(5)` call per `timeStep()`
  (was computed 3× per frame — duplicated work).
- The runaway "nudge back to center" guard was widened from ±500px to the **virtual canvas**
  bounds (one full screen beyond each edge), so bees are free to orbit off-screen; the goal
  force, not the boundary, governs normal positioning.

---

## 4. BeeManager.java — what's new

`startSimulation()` now runs a **continuous, score-driven loop** (instead of a fixed 10s run).
Each ~32ms frame it reads the score and calls `driveSwarm(score, intervention)`, then steps and
renders the bees, then reaps any that have left the screen. The loop self-stops only when the
score is in the calm band (≤ `AMBIENT_SCORE`) and no bees remain; OverlayService restarts it on
the next level rise.

`driveSwarm()` is the brain. From the score it derives:

- **Bee count** (`targetCount`): 0 at/below score 20, scaling up to `MAX_BEES` near 100 — *"the
  longer you're unproductive, the more bees."* Bees spawn off-screen and trickle in; excess bees
  are marked despawning.
- **Orbiting (virtual canvas):** each frame every bee's goal is placed on an elliptical ring of
  radius `0.75 × screen` around the screen centre, advanced by its `phase` + a global frame
  counter. Radius > half-screen ⇒ bees **mostly orbit off-screen**.
- **Sloshing in:** a slow per-bee sine wave occasionally pulls a bee's goal inside the screen.
  Higher score ⇒ lower threshold ⇒ bees dip in more often and more aggressively.
- **Angriness:** `clamp((score − ANGER_START)/(100 − ANGER_START))`, handed to every bee via
  `setAngriness()` (boosted during the swarm event, the intervention, and a swipe "nest" stir-up).
- **Brief swarm event (last escalation step):** when score crosses `SWARM_EVENT_SCORE` (90, below
  max), **once per crossing**, all goals move inside the screen for ~1.8s, then revert to orbiting.
  Re-arms after the score drops back (hysteresis).
- **Intervention** (`isShowInterventionOverlay()` == true, i.e. score hit max): all bees cover the
  whole screen for ~1.6s, then **all** bees despawn (off-screen goals → reaped) and stay gone while
  the flag holds. OverlayService still owns the dark overlay + close button; this is just the bee side.
- **Despawn rule:** a despawning bee gets an off-screen goal and is only removed (list + window)
  once `isOffScreen()` is true — *bees never pop out of existence mid-screen.*

**Touches / swipes** (per design decision):
- Each bee window keeps `FLAG_NOT_TOUCHABLE` — ambient bees **never** consume touches, so the app
  underneath stays usable.
- When `score ≥ SWIPE_LAYER_SCORE` (70), BeeManager adds **one** full-screen touch window with a
  `GestureDetector`. `onFling` pushes the whole swarm in the swipe direction (impulse + scatter
  off-screen). Below `NEST_SCORE` (85) they just disperse and drift back; at/above it they regather
  fast and angry — a *"stirred-up nest."* The touch window is removed when the score drops below 70.

`removeAllBees()` stays a hard teardown (for `onDestroy` / level 0) that also removes the swipe layer.

### Tuning constants (top of `BeeManager.java`)

| Constant | Default | Meaning |
| --- | --- | --- |
| `AMBIENT_SCORE` | 20 | at/below: no bees (matches engine level 0) |
| `SLOSH_START` | 35 | above: bees begin dipping into view |
| `ANGER_START` | 40 | above: angriness ramps up |
| `SWIPE_LAYER_SCORE` | 70 | swipe-to-disperse layer becomes active |
| `NEST_SCORE` | 85 | swipes trigger the angry "nest" regather |
| `SWARM_EVENT_SCORE` | 90 | brief all-in screen swarm (below max) |
| `MAX_BEES` | 24 | bee cap at score 100 |
| `ORBIT_RADIUS_FACTOR` | 0.75 | ring radius vs half-screen (off-screen orbit) |
| `SLOSH_INNER_FACTOR` | 0.35 | how far inside a sloshing bee dips |
| `SWIPE_IMPULSE` | 45 | swipe push strength |

---

## 5. Threading model (for future maintainers)

- **`bees` / `despawning`**: mutated only inside `synchronized (beesLock)`. The sim loop iterates a
  *copy* (snapshot); each bee carries its own neighbour snapshot, so physics never touches the
  shared list.
- **`beeViews` and all WindowManager calls**: main thread only (via `mainHandler`).
- **`screenW/H`**: cached on the main thread in `startSimulation()` before the thread starts.
- `updateBeeVisuals()` re-checks `bees.contains(bee)` before creating a window, so a bee reaped on
  the sim thread can never get a stray window recreated on the main thread.

---

## 6. How to navigate / verify

- Start in `BeeManager.driveSwarm()` — it's the single place where score becomes behaviour.
- Goal math: `driveSwarm()` (orbit/slosh/cover) and `setOffScreenGoal()` (despawn direction).
- Per-bee feel: `SingleBee.timeStep()` (forces + angriness).
- To re-tune escalation, edit the constants table above — no logic changes needed.

**Manual test:** run the app, grant overlay + accessibility, open an app categorised
UNPRODUCTIVE (severity 3 = +20/min). Watch: a few bees orbiting mostly off-screen → more bees +
sloshing as the score climbs → a brief full-screen swarm near the top → at max, bees cover the
screen then vanish under the dark intervention. Confirm the underlying app is usable at low
severity, and that swipes scatter bees once severity is high.
