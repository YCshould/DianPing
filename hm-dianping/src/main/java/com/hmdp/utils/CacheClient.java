package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 封装缓存穿透与击穿所使用的方法
 */
@Slf4j
@Component
public class CacheClient {


    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set (String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setlogicalexpire (String key, Object value, Long time, TimeUnit unit){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 实现缓存穿透函数
     * @param
     * @return
     */
    public <R,ID> R querywithPassThrough(String keyprefix, ID id, Class<R> type, Function<ID,R> deFallback,
                                         Long time, TimeUnit unit){   //<R> R表示返回值为任意的类型，由Class<R> type指定,ID也是泛型
        //1.从redis中查缓存看是否有商家信息
        String json = stringRedisTemplate.opsForValue().get(keyprefix + id);
        if (StrUtil.isNotBlank(json)) { //不为空返回
            return JSONUtil.toBean(json, type); //type是泛型
        }
        //判断命中是否为空值
        if (json !=null) {  //!=null就是s为空
            return null;
        }

        //2.redis没有从mysql数据库查  数据库操作也要调用者指定
        R r = deFallback.apply(id);
        //3.数据库没有则报错
        if (r == null) {
            //将null值写入到redis解决缓存穿透问题
            stringRedisTemplate.opsForValue().set("cache:shop:"+id,"",2, TimeUnit.MINUTES);
            return null;
        }
        //4.数据库有将数据返回给客户端，并将商家信息存在redis中
        this.set(keyprefix+id,r,time,unit);
        return r;
    }

    //线程池
    private static  final ExecutorService CACHE_REBULID_EXECUTOR= Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <ID,R> R querywithExpire(String keyprefix,ID id,Class<R> type,Function<ID,R> defallback,
                                    Long time, TimeUnit unit){
        //1.从redis中查缓存看是否有商家信息
        String s = stringRedisTemplate.opsForValue().get(keyprefix + id);
        //未命中
        if (StrUtil.isBlank(s)) { //为空返回
            return null;
        }
        //2.命中先将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(s, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData(); //将取回的object类型的data再转化为json类型就得到了shop类型的json
        R r = JSONUtil.toBean(data, type); //最后转化为shop类型对象
        LocalDateTime expireTime = redisData.getExpireTime();
        //3.判断是否过期，若没有过期直接返回商铺信息
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期
            return r;
        }
        //4.要是过期了，获取锁，获取锁失败返回商铺信息
        String lockkey="lock:shop:"+id;
        if (trylock(lockkey)) {
            //5.获取锁成功，开启独立线程实现缓存重建
            CACHE_REBULID_EXECUTOR.submit(()->{
                try {
                    R r1 = defallback.apply(id);
                    this.setlogicalexpire(keyprefix+id,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException();
                }finally {
                    unlock(lockkey);
                }
            });
        }
        //获取锁失败返回商铺信息
        return r;
    }


    //生成自定义的锁，解决缓存击穿的问题，setIfAbsent如果不存在才会给生成键值对
    private boolean trylock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    //释放自定义的锁，删掉就相当于释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);

    }
}
