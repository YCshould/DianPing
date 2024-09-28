package com.hmdp.utils;

public interface ILock {

    /**
     * 获取redis的分布式锁
     * @param timeoutSec
     * @return
     */
    boolean trylock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
