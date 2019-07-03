package com.sayhiai.example.ratelimit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class SemaphoreLimiterGoodAdvance {

    Semaphore semaphore = new Semaphore(5);

    final String TOO_MANY_REQUESTS = "500 : too many requests error";

    public String req() throws InterruptedException {
        long begin = System.currentTimeMillis();
        boolean ok = semaphore.tryAcquire(1, TimeUnit.SECONDS);
        try {
            Thread.sleep(100);
            long cost = System.currentTimeMillis() - begin;
            if (ok) {
                return "success:" + cost + "ms";
            } else {
                return TOO_MANY_REQUESTS;
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
        SemaphoreLimiterGoodAdvance limiter = new SemaphoreLimiterGoodAdvance();
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
