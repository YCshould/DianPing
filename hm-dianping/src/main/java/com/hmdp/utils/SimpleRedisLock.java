package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;  //业务名称
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private  static final String  KEY_PREFIX="lock:";
    private  static final String  ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    private  static final DefaultRedisScript<Long> UNLOCK_SCRIPT;  //自定义lua(redis)脚本文件，先编译提高速度
    static{
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);  //定义返回值类型
    }

    @Override
    public boolean trylock(long timeoutSec) {
        //获取当前线程名
        String threadid = ID_PREFIX+Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, threadid, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);  //不能直接返回success,success是Boolean类型而返回值是boolean,会存在拆箱过程可以返回空指针
    }

    /**
     * 调用Lua脚本实现释放锁的原子性操作
     */
    @Override
    public void unlock() {
            stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(KEY_PREFIX+name),//lua脚本接受的参数KEYS[1]
                    ID_PREFIX+Thread.currentThread().getId()
            );
    }

    /*@Override
    public void unlock() {
        //当前线程的id
        String threadid = ID_PREFIX+Thread.currentThread().getId();

        //获取占有锁线程的threadid
        String id=stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);  //要是这一步刚执行完发生阻塞也有可能造成释放另一个线程的锁问题，可以用Lua脚本实现一致性

        //要是二个id不一致说明要释放锁的线程和占有锁的线程不是同一个线程，此时不能释放锁，一致可以释放锁
        //防止某个线程因阻塞导致的超时释放锁，而去释放另一个线程的锁
        if(threadid.equals(id)){
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }


    }*/
}
