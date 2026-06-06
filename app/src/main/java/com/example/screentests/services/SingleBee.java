package com.example.screentests.services;

import static java.lang.Math.*;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Random;

public class SingleBee {
    int maxX = 0;
    int MaxY = 0;
    int posX;
    int posY;
    private ArrayList<SingleBee> swarmBees = new ArrayList<SingleBee>();

    public SingleBee(int x, int y, int maxVisualOverhead) {
        maxX = x;
        MaxY = y;

        Random random = new Random();
        this.posX = random.nextInt(maxX + maxVisualOverhead);
        this.posY = random.nextInt(MaxY + maxVisualOverhead);

    }

    public void updateExistingBees(ArrayList<SingleBee> newBees) {
        swarmBees = newBees;
    }

    public int calculateDistance(SingleBee otherBee) {
        int distanceX = Math.abs(posX - otherBee.posX);
        int distanceY = Math.abs(posY - otherBee.posY);
        return (int) Math.sqrt(distanceX * distanceX + distanceY * distanceY);
    }

    //https://en.wikipedia.org/wiki/Boids if you dont know what is going on here
    protected void calculateSeparationForce() {
        //TODO: calculate separation force
    }
    protected void calculateAlignmentForce() {
        //TODO: calculate alignment force
    }
    protected void calculateCohesionForce() {
        //TODO: calculate cohesion force
    }

    protected void calculateTotalForce() {
        //TODO: calculate total force
    }
    public int getPosition(BeeManager.dim dimension) {
        return (dimension == BeeManager.dim.WIDTH) ? posX : posY;
    }
}


