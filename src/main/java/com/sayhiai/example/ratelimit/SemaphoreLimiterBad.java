package com.sayhiai.example.ratelimit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class SemaphoreLimiterBad {

    Semaphore semaphore = new Semaphore(5);

    public String req() {
        try {
            long begin = System.currentTimeMillis();
            semaphore.acquire(1);
            Thread.sleep(100);
            long cost = System.currentTimeMillis() - begin;
            return "success:" + cost + "ms";
        } catch (Exception ex) {
            System.out.println("fail acquire");
            return "fail";
        } finally {
            semaphore.release(1);
        }
    }


    public static void main(String[] args) {
        SemaphoreLimiterBad limiter = new SemaphoreLimiterBad();
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
