package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 *  服务实现类
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result querybyid(Long id) {
        //实现缓存穿透
        Shop shop=cacheClient.querywithPassThrough("shop:cache:",id,Shop.class,this::getById,30L,TimeUnit.MINUTES);
        //实现缓存击穿
        //Shop shop = querywithMutex(id);//互斥锁
        //Shop shop=cacheClient.querywithExpire("shop:cache:",id,Shop.class,this::getById,30L,TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺信息不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 实现缓存穿透函数
     * @param id
     * @return
     */
//    public Shop querywithPassThrough(Long id){
//        //1.从redis中查缓存看是否有商家信息
//        String s = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
//        if (StrUtil.isNotBlank(s)) { //不为空返回
//            Shop shop = JSONUtil.toBean(s, Shop.class);
//            return shop;
//        }
//        //判断命中是否为空值
//        if (s !=null) {  //!=null就是s为空
//            return null;
//        }
//
//        //2.redis没有从mysql数据库查
//        Shop shop = getById(id);
//        //3.数据库没有则报错
//        if (shop == null) {
//            //将null值写入到redis解决缓存穿透问题
//            stringRedisTemplate.opsForValue().set("cache:shop:"+id,"",2, TimeUnit.MINUTES);
//            return null;
//        }
//        //4.数据库有将数据返回给客户端，并将商家信息存在redis中
//        stringRedisTemplate.opsForValue().set("cache:shop:"+id,JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
//        return shop;
//    }

    /**
     * 建立线程池
     */
//    private static  final ExecutorService CACHE_REBULID_EXECUTOR= Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
//    public Shop querywithExpire(Long id){
//        //1.从redis中查缓存看是否有商家信息
//        String s = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
//        //未命中
//        if (StrUtil.isBlank(s)) { //为空返回
//            return null;
//        }
//        //2.命中先将json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(s, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData(); //将取回的object类型的data再转化为json类型就得到了shop类型的json
//        Shop shop = JSONUtil.toBean(data, Shop.class); //最后转化为shop类型对象
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //3.判断是否过期，若没有过期直接返回商铺信息
//        if(expireTime.isAfter(LocalDateTime.now())){
//            //未过期
//            return shop;
//        }
//        //4.要是过期了，获取锁，获取锁失败返回商铺信息
//        String lockkey="lock:shop:"+id;
//        if (trylock(lockkey)) {
//            //5.获取锁成功，开启独立线程实现缓存重建
//            CACHE_REBULID_EXECUTOR.submit(()->{
//            try {
//                    this.saveredisshop(id,20L);
//            } catch (Exception e) {
//                throw new RuntimeException();
//            }finally {
//                unlock(lockkey);
//            }
//            });
//        }
//        //获取锁失败返回商铺信息
//        return shop;
//    }
    /**
     * 实现缓存击穿函数
     * @param id
     * @return
     */
    public Shop querywithMutex(Long id){
        //1.从redis中查缓存看是否有商家信息
        String s = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        if (StrUtil.isNotBlank(s)) { //不为空返回
            Shop shop = JSONUtil.toBean(s, Shop.class);
            return shop;
        }
        //判断命中是否为空值
        if (s !=null) {  //!=null就是s为空
            return null;
        }

        //获取互斥锁，成功则执行2.redis没有从mysql数据库查，失败则休眠一段时间，再执行1.从redis中查缓存看是否有商家信息
        String keylock="lock:shop:"+id;
        Shop shop = null;
        try {
            if (!trylock(keylock)) {
                Thread.sleep(30);
                return querywithMutex(id); //递归
            }

            //2.redis没有从mysql数据库查
            shop = getById(id);
            //3.数据库没有则报错
            if (shop == null) {
                //将null值写入到redis解决缓存穿透问题
                stringRedisTemplate.opsForValue().set("cache:shop:"+id,"",2, TimeUnit.MINUTES);
                return null;
            }
            //4.数据库有将数据返回给客户端，并将商家信息存在redis中
            stringRedisTemplate.opsForValue().set("cache:shop:"+id,JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException();
        }finally {
            unlock(keylock);//释放互斥锁
        }


        return shop;
    }

    @Override
    @Transactional
    public Result Update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return  Result.fail("id不存在");
        }
        //1.先更新数据库
        updateById(shop);
        //2.再删除缓存
        stringRedisTemplate.delete("cache:shop:" + shop.getId());
        return Result.ok();
    }


    /**
     * 给店铺添加一个逻辑过期时间
     * @param id
     * @param expire
     */
    private void saveredisshop(Long id,Long expire){
        Shop shop = getById(id);

        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expire));

        stringRedisTemplate.opsForValue().set("cache:shop:"+id,JSONUtil.toJsonStr(redisData));
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
