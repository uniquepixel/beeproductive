# Swarm Showcase

A standalone, copy-paste-ready demo of the BeeProductive bee swarm. The swarm classes in here
are **cloned (by AI) from the main app** (`app/src/main/java/com/example/screentests/services/`)
and reworked so they run in *any* fresh Android project with **zero extra dependencies and zero
special permissions**:

- 🍯 **Honey texture background** — the whole screen is a honeycomb texture
  (`res/drawable/showcase_honey_background.xml`), the bees fly above it.
- 🐝 **Same boids swarm** — `SingleBee` physics are identical to the main app; the bees fly the
  same **radial (orbit-ring) goal pattern**.
- 🎚 **Sliders at the bottom of the screen** control **swarm size** and **angriness** live.
- 👆 **Swipe-and-disperse layer** — *permanently* active: fling anywhere and the swarm is blown
  along your swipe.
- 📳 **Shake detector** — *permanently* active: shake the device and the bees scatter radially
  from the screen centre.

Unlike the main app, the bees are ordinary `ImageView`s inside the activity (no
`SYSTEM_ALERT_WINDOW` overlay permission, no accessibility service, no foreground service).

## What's in here

```
swarm-showcase/
├── java/com/example/swarmshowcase/
│   ├── SwarmShowcaseActivity.java   ← the single entry point (start this via an Intent)
│   ├── BeeManager.java              ← swarm driver (cloned + reworked: slider-driven, view-based)
│   ├── SingleBee.java               ← boids physics (cloned, unchanged)
│   └── ShakeDetector.java           ← accelerometer shake listener (cloned, unchanged)
└── res/
    ├── layout/activity_swarm_showcase.xml       ← honey background + bee container + sliders
    └── drawable/
        ├── showcase_honey_background.xml        ← the honey texture
        └── worker_bee.png                       ← the bee sprite
```

## Drop it into a new project (3 steps)

1. **Copy the files**
   - `java/com/example/swarmshowcase/` → your project's `app/src/main/java/` (keep the package
     folders).
   - `res/layout/` and `res/drawable/` contents → your project's `app/src/main/res/layout/` and
     `res/drawable/`.
   - If your project's `namespace` is **not** `com.example.swarmshowcase`, add
     `import <your.namespace>.R;` to `SwarmShowcaseActivity.java` and `BeeManager.java`
     (they are the only two classes that touch resources).

2. **Register the activity** in your `AndroidManifest.xml`:

   ```xml
   <activity
       android:name="com.example.swarmshowcase.SwarmShowcaseActivity"
       android:exported="false" />
   ```

3. **Start it with a single intent** from your `MainActivity`:

   ```java
   startActivity(new Intent(this, SwarmShowcaseActivity.class));
   ```

That's it — no permissions, no services, no gradle dependencies (the showcase uses only stock
`android.*` widgets).

## Controls

| Control | Effect |
|---|---|
| **Swarm size slider** (bottom) | Target number of bees (0–40). Bees fly in from / out to the edge of the ring as you drag. |
| **Angriness slider** (bottom) | 0–100 %: flight speed, jitter, personal space, how deep bees "slosh" into the screen, and how briefly they linger at the edge after being dispersed. |
| **Swipe** anywhere over the honey | Blows the swarm in the swipe direction; it lingers near the edge, then returns to the radial pattern. |
| **Shake** the device | Scatters the swarm radially from the centre. At ≥ 85 % angriness this "stirs up the nest": bees come back faster and angrier for a moment. |
