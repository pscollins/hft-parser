package com.hftparser.containers;

import java.util.Random;

/**
 * Created by patrick on 7/30/14.
 */

// Art of Multiprocessor Programming p. 148
public class Backoff implements Backoffable {
    private final int minDelay;
    private final int maxDelay;
    private final Random random;
    private int limit;

    public Backoff(int min, int max) {
        minDelay = min;
        maxDelay = max;

        limit = minDelay;

        random = new Random();
    }

    @Override
    public void backoff() {
        int delay = random.nextInt(limit);
        limit = Math.min(maxDelay, 2 * limit);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            System.out.println("Got interrupted: " + e.toString());
        }
    }

}
