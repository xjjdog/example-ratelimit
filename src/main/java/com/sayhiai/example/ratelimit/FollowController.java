package com.sayhiai.example.ratelimit;

import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FollowController {

    private final RateLimiter rateLimiter;

    private int maxPermits;

    private Object mutex = new Object();

    //等待获取permits的请求个数，原则上可以通过maxPermits推算
    private int maxWaitingRequests;

    private AtomicInteger waitingRequests = new AtomicInteger(0);

    public FollowController(int maxPermits, int maxWaitingRequests) {
        this.maxPermits = maxPermits;
        this.maxWaitingRequests = maxWaitingRequests;
        rateLimiter = RateLimiter.create(maxPermits);
    }

    public FollowController(int permits, long warmUpPeriodAsSecond, int maxWaitingRequests) {
        this.maxPermits = maxPermits;
        this.maxWaitingRequests = maxWaitingRequests;
        rateLimiter = RateLimiter.create(permits, warmUpPeriodAsSecond, TimeUnit.SECONDS);
    }

    public boolean acquire() {
        return acquire(1);
    }

    public boolean acquire(int permits) {
        boolean success = rateLimiter.tryAcquire(permits);
        if (success) {
            rateLimiter.acquire(permits);//可能有出入
            return true;
        }
        if (waitingRequests.get() > maxWaitingRequests) {
            return false;
        }
        waitingRequests.getAndAdd(permits);
        rateLimiter.acquire(permits);

        waitingRequests.getAndAdd(0 - permits);
        return true;
    }

}

