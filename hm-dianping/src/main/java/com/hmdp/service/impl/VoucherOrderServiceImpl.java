package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWork;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWork redisIdWork;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    //建立线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    @PostConstruct   //该注解的作用是当VoucherOrderServiceImpl大类启动时马上提交线程任务，让线程启动
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());  //new VoucherOrderHandler()是线程提交的一个任务
    }
/*    //阻塞队列
    private BlockingQueue<VoucherOrder> orderstask=new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //获取订单信息
                    VoucherOrder voucherOrder = orderstask.take();
                    //创建订单
                    handlerVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }*/

    /**
     * 后期用基于stream的Xgroup的消息队列，不用在java代码中创造阻塞队列（在Jvm中会占用内存），直接在lua脚本中传递消息,先在redis中创建一个xgroup
     */
    //消息队列
    private class VoucherOrderHandler implements Runnable{
        String queueName="stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //获取消息订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(list==null||list.isEmpty()){
                        //消息获取失败进行下一次循环
                        continue;
                    }
                    //解析消息队列list数据为VoucherOrder  String为id
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //消息获取成功可以下单
                    handlerVoucherOrder(voucherOrder);
                    //ACK确认该消息已经接受
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    //如果没有被ack确认就是出项异常，进入Pending消息未处理队列
                    log.error("处理订单异常",e);
                    handlerPendingList();
                }
            }
        }

        private void handlerPendingList() {
            while (true){
                try {
                    //获取pending_list订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if(list==null||list.isEmpty()){
                        //说明pending_list没有消息，结束循环
                        break;
                    }
                    //解析消息队列list数据为VoucherOrder  String为id
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //消息获取成功可以下单
                    handlerVoucherOrder(voucherOrder);
                    //ACK确认该消息已经接受
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    //如果没有被ack确认就是出项异常，进入Pending消息未处理队列
                    log.error("处理pending_list订单异常",e);
                }
            }
        }
    }

    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getId();
        //创建(依赖)Redisson分布式锁对象
        //源码redisson里有rua脚本源码实现分布锁可重用(即同一个线程，用户可以获得同一个锁，将锁的数量加一就行，释放就减一)
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean trylock = lock.tryLock();

        if (!trylock) {
            //获取锁失败
            log.error("不能重复下单");
            return ;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);  //直接调用会缺少@Transactional事务，所以要加AopContext.currentProxy();,还注入了一个依赖
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private  static final DefaultRedisScript<Long> SECKILL_SCRIPT;  //自定义lua(redis)脚本文件，先编译提高速度
    static{
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);  //定义返回值类型
    }

    private IVoucherOrderService proxy;

    /**
     * 主线程 阻塞队列实现
     * @param voucherId
     * @return
     */
    //用rua脚本实现
    ////////////////////////////////////////////注意:在程序运行前先将redis中的库存(string)和用户id(set)先配置好
    /*@Override
    public Result seckillVoucher(Long voucherId) {

        //1.查询秒杀券
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);

        //2.判断秒杀是否开始，与是否结束
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //1.执行脚本，redis的值是预先存好的
        Long userid = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userid.toString()
        );

        //2.不为0，没有购买资格
        int i = result.intValue();
        if(i!=0){
            return Result.fail(i==1? "库存不足":"该用户已经抢过该秒杀券");
        }
        //3.返回值为0，可购买将下单信息保存在阻塞队列
        long nextid = redisIdWork.nextid("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(nextid);  //订单号
        voucherOrder.setVoucherId(voucherId);   //秒杀券id
        voucherOrder.setUserId(userid); //用户id
        orderstask.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(nextid);
    }*/


    /**
     * 主线程，Stream消息队列实现
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {

        //1.查询秒杀券
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
        //获取订单id
        long orderid = redisIdWork.nextid("order");
        //2.判断秒杀是否开始，与是否结束
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //1.执行脚本，redis的值是预先存好的
        Long userid = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userid.toString(),String.valueOf(orderid)
        );

        //2.不为0，没有购买资格
        int i = result.intValue();
        if(i!=0){
            return Result.fail(i==1? "库存不足":"该用户已经抢过该秒杀券");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderid);
    }

    /*public Result seckillVoucher(Long voucherId) {
        //1.查询秒杀券
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);

        //2.判断秒杀是否开始，与是否结束
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }

        //3.秒杀券库存是否充足
        Integer stock = seckillVoucher.getStock();
        if(stock<1){  //在此处设计stock>0也不会成功，stock也会跳过此处，并行执行 /4.扣减库存 导致超卖问题
            return Result.fail("库存不足");
        }
        //6.返回订单号
        Long userId = UserHolder.getUser().getId();//用户拦截器可以获得用户id
        //只用来防止相同用户，不同用户抢不用加上不同的锁，intern()的作用是如果字符池中已经有相同的字符那就取原来的字符，此时用户是同一个用户则加上同一个锁锁
        //synchronized(userId.toString().intern()) {  //不能保证集群分布（多进程），要使用分布式锁

        //创建分布式锁对象
        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        //创建(依赖)Redisson分布式锁对象
        //源码redisson里有rua脚本源码实现分布锁可重用(即同一个线程，用户可以获得同一个锁，将锁的数量加一就行，释放就减一)
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean trylock = lock.tryLock();

        if (!trylock) {
            //获取锁失败
            return Result.fail("一个人只能下一单");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);  //直接调用会缺少@Transactional事务，所以要加AopContext.currentProxy();,还注入了一个依赖
        } finally {
            //释放锁
            lock.unlock();
        }

        //}
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //一人一单问题
        Long userId = voucherOrder.getUserId();//用户拦截器可以获得用户id
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                log.error("一个用户只能抢一种商品的一种秒杀券");
                return;
            }
            //4.数据库里面扣减库存
            boolean sucess = iSeckillVoucherService.update().
                    setSql("stock=stock-1").
                    eq("voucher_id", voucherOrder.getVoucherId()).
                    gt("stock", 0).  //gt()表示stock是否大于0解决超卖问题
                    update();

            if (!sucess) {
                log.error("库存不足");
                return ;
            }
            save(voucherOrder);


    }
}
