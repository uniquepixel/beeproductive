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

    public SingleBee(int x, int y, int maxVisualOverhead) {
        this.maxX = x;
        this.MaxY = y;

        Random random = new Random();
        // Use double for precision and allow starting slightly off-screen
        this.posX = random.nextInt(Math.max(1, maxX + maxVisualOverhead));
        this.posY = random.nextInt(Math.max(1, MaxY + maxVisualOverhead));
        
        // Initial random velocity
        this.delta_x = (random.nextDouble() - 0.5) * 10;
        this.delta_y = (random.nextDouble() - 0.5) * 10;
    }

    public void updateExistingBees(List<SingleBee> newBees) {
        // Store a copy to prevent ConcurrentModificationException during simulation
        this.swarmBees = new ArrayList<>(newBees);
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
    protected void calculateSeparationForce() {
        List<SingleBee> neighbors = getClosestNeighbors(5);
        for (SingleBee neighbor : neighbors) {
            double dist = calculateDistance(neighbor);
            if (dist < 100 && dist > 0) {
                delta_x += (this.posX - neighbor.posX) * 0.05;
                delta_y += (this.posY - neighbor.posY) * 0.05;
            }
        }
    }

    protected void calculateAlignmentForce() {
        List<SingleBee> neighbors = getClosestNeighbors(5);
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

    protected void calculateCohesionForce() {
        List<SingleBee> neighbors = getClosestNeighbors(5);
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
    
    public int getPosition(BeeManager.dim dimension) {
        return (dimension == BeeManager.dim.WIDTH) ? (int) posX : (int) posY;
    }
    
    public void timeStep(){
        calculateSeparationForce();
        calculateAlignmentForce();
        calculateCohesionForce();
        
        //speed cap
        double maxSpeed = 15.0;
        double speed = Math.sqrt(delta_x * delta_x + delta_y * delta_y);
        if (speed > maxSpeed) {
            delta_x = (delta_x / speed) * maxSpeed;
            delta_y = (delta_y / speed) * maxSpeed;
        }

        posX += delta_x;
        posY += delta_y;

        //Boundary safety nudges
        if (posX < -500 || posX > maxX + 500 || posY < -500 || posY > MaxY + 500) {
            delta_x += (maxX / 2.0 - posX) * 0.005;
            delta_y += (MaxY / 2.0 - posY) * 0.005;
        }
    }
}
