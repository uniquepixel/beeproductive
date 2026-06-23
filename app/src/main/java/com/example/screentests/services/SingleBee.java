package com.example.screentests.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class SingleBee {
    private int maxX = 0;
    private int MaxY = 0;
    private double posX;
    private double posY;
    private double delta_x = 0;
    private double delta_y = 0;
    private List<SingleBee> swarmBees = new ArrayList<>();

    // --- Goal / seek mechanic (set by BeeManager) ---
    private double goalX;
    private double goalY;
    private boolean hasGoal = false;

    // --- Local aggression modifier 0..1 (set by BeeManager, never overrides swarm logic) ---
    private double angriness = 0.0;

    // Stable per-bee orbit identity so BeeManager can keep each bee on its own phase of the ring.
    private final double phase;

    // Reused so we don't allocate a Random every frame for the angriness jitter.
    private final Random random = new Random();

    // Base tuning constants.
    private static final double BASE_MAX_SPEED = 15.0;
    private static final double GOAL_WEIGHT = 0.01;   // same additive form as cohesion, steers toward the goal
    private static final double JITTER_STRENGTH = 3.0; // max chaotic wobble at full angriness, just a value

    public SingleBee(int x, int y, int maxVisualOverhead) {
        this.maxX = x;
        this.MaxY = y;

        // Use double for precision and allow starting slightly off-screen
        this.posX = random.nextInt(Math.max(1, maxX + maxVisualOverhead));
        this.posY = random.nextInt(Math.max(1, MaxY + maxVisualOverhead));

        // Initial random velocity
        this.delta_x = (random.nextDouble() - 0.5) * 10;
        this.delta_y = (random.nextDouble() - 0.5) * 10;

        // Random, stable phase around the orbit ring.
        this.phase = random.nextDouble() * Math.PI * 2;
    }

    public void updateExistingBees(List<SingleBee> newBees) {
        // Store a copy to prevent ConcurrentModificationException during simulation
        this.swarmBees = new ArrayList<>(newBees);
    }

    /** Set the point this bee should steer toward (in virtual-canvas coordinates). */
    public void setGoal(double x, double y) {
        this.goalX = x;
        this.goalY = y;
        this.hasGoal = true;
    }

    /** 0 = calm swarm behaviour, 1 = fast, jittery and aggressive. Clamped. */
    public void setAngriness(double value) {
        this.angriness = Math.max(0.0, Math.min(1.0, value));
    }

    /** Lets BeeManager place a freshly spawned bee (e.g. off-screen on the ring). */
    public void setPosition(double x, double y) {
        this.posX = x;
        this.posY = y;
    }

    public double getPhase() {
        return phase;
    }

    /** Kick the bee's velocity, e.g. when a swipe pushes it away. */
    public void applyImpulse(double dx, double dy) {
        this.delta_x += dx;
        this.delta_y += dy;
    }

    public double calculateDistance(SingleBee otherBee) {
        double distanceX = this.posX - otherBee.posX;
        double distanceY = this.posY - otherBee.posY;
        return Math.sqrt(distanceX * distanceX + distanceY * distanceY);
    }

    private List<SingleBee> getClosestNeighbors(int count) {
        List<SingleBee> neighbors = new ArrayList<>(swarmBees);
        neighbors.remove(this);
        if (neighbors.isEmpty()) return neighbors;

        Collections.sort(neighbors, new Comparator<SingleBee>() {
            @Override
            public int compare(SingleBee b1, SingleBee b2) {
                return Double.compare(calculateDistance(b1), calculateDistance(b2));
            }
        });

        if (neighbors.size() > count) {
            return neighbors.subList(0, count);
        }
        return neighbors;
    }

    //https://en.wikipedia.org/wiki/Boids if you dont know what is going on here
    // Neighbors are computed once per timeStep and shared by the three forces below.
    protected void calculateSeparationForce(List<SingleBee> neighbors) {
        double strength = 0.05 * (1 + angriness); // angrier bees keep more personal space
        for (SingleBee neighbor : neighbors) {
            double dist = calculateDistance(neighbor);
            if (dist < 100 && dist > 0) {
                delta_x += (this.posX - neighbor.posX) * strength;
                delta_y += (this.posY - neighbor.posY) * strength;
            }
        }
    }

    protected void calculateAlignmentForce(List<SingleBee> neighbors) {
        if (neighbors.isEmpty()) return;

        double avgDX = 0;
        double avgDY = 0;
        for (SingleBee neighbor : neighbors) {
            avgDX += neighbor.delta_x;
            avgDY += neighbor.delta_y;
        }
        avgDX /= neighbors.size();
        avgDY /= neighbors.size();

        delta_x += (avgDX - delta_x) * 0.05;
        delta_y += (avgDY - delta_y) * 0.05;
    }

    protected void calculateCohesionForce(List<SingleBee> neighbors) {
        if (neighbors.isEmpty()) return;

        double avgX = 0;
        double avgY = 0;
        for (SingleBee neighbor : neighbors) {
            avgX += neighbor.posX;
            avgY += neighbor.posY;
        }
        avgX /= neighbors.size();
        avgY /= neighbors.size();

        delta_x += (avgX - posX) * 0.01;
        delta_y += (avgY - posY) * 0.01;
    }

    /** 4th force: steer toward the goal handed in by BeeManager. */
    protected void calculateGoalForce() {
        if (!hasGoal) return;
        double weight = GOAL_WEIGHT * (1 + angriness); // angrier bees chase the goal harder
        delta_x += (goalX - posX) * weight;
        delta_y += (goalY - posY) * weight;
    }

    public int getPosition(BeeManager.dim dimension) {
        return (dimension == BeeManager.dim.WIDTH) ? (int) posX : (int) posY;
    }

    /** True when the bee has left the visible screen rectangle (despawn is only allowed off-screen). */
    public boolean isOffScreen() {
        return posX < 0 || posX > maxX || posY < 0 || posY > MaxY;
    }

    public void timeStep() {
        // Compute neighbors once and share across the three boids forces (was 3x before).
        List<SingleBee> neighbors = getClosestNeighbors(5);

        calculateSeparationForce(neighbors);
        calculateAlignmentForce(neighbors);
        calculateCohesionForce(neighbors);
        calculateGoalForce();

        // Angriness adds chaotic wobble on top of (not instead of) the swarm forces.
        if (angriness > 0) {
            delta_x += (random.nextDouble() - 0.5) * JITTER_STRENGTH * angriness;
            delta_y += (random.nextDouble() - 0.5) * JITTER_STRENGTH * angriness;
        }

        // Speed cap rises with angriness so angry bees move faster.
        double maxSpeed = BASE_MAX_SPEED * (1 + angriness * 1.5);
        double speed = Math.sqrt(delta_x * delta_x + delta_y * delta_y);
        if (speed > maxSpeed) {
            delta_x = (delta_x / speed) * maxSpeed;
            delta_y = (delta_y / speed) * maxSpeed;
        }

        posX += delta_x;
        posY += delta_y;

        // Far runaway safety net only. Normal positioning is governed by the goal force, so the
        // guard sits at the edge of the virtual canvas (one full screen beyond each edge) instead
        // of yanking bees back the moment they leave the visible screen.
        if (posX < -maxX || posX > maxX * 2 || posY < -MaxY || posY > MaxY * 2) {
            delta_x += (maxX / 2.0 - posX) * 0.005;
            delta_y += (MaxY / 2.0 - posY) * 0.005;
        }
    }
}
