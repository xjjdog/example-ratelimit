package com.sayhiai.example.ratelimit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class SemaphoreLimiterGood {

    Semaphore semaphore = new Semaphore(5);

    public String req() {
        long begin = System.currentTimeMillis();
        boolean ok = semaphore.tryAcquire(1);
        try {
            Thread.sleep(100);
            long cost = System.currentTimeMillis() - begin;
            if (ok) {
                return "success:" + cost + "ms";
            } else {
                return "fail";
            }
        } catch (Exception ex) {
            //
            return "Exception";
        } finally {
            if (ok) {
                semaphore.release(1);
            }
        }

    }


    public static void main(String[] args) {
        SemaphoreLimiterGood limiter = new SemaphoreLimiterGood();
        ExecutorService executor = Executors.newCachedThreadPool();
        for (int i = 0; i < 1000; i++) {
            executor.submit(() -> {
                while (true) {
                    System.out.println(limiter.req());
                }
            });
        }
    }
}
