package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 订单redis的唯一序列号
 */
@Component
public class RedisIdWork {

    //初始时间戳,单位是秒，随意设计
    private final static long BEGIN_TIME=1640995200L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //唯一共64位，符号位(1bit)+时间戳(31bit)+序列号(32bit)
    public long nextid(String keyprefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowsecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowsecond - BEGIN_TIME; //时间戳
        //2.生成redis序列号
        String day = now.format(DateTimeFormatter.ofPattern("yyyyMMdd")); //自定义时间精确到天
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyprefix + ":" + day);
        //3.拼接并返回，都是long类型不能用字符串拼接，数值类型拼接用位运算
        return timestamp<<32|count;   //时间戳移动32位后，低32位为0，进行或运算即相加

    }
}
