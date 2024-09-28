package com.hmdp.service.impl;


import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;



import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> getshoplist() {


        //1.从redis中查缓存看是否有商家信息
        List<String> s = stringRedisTemplate.opsForList().range("cache:shoptype:" ,0,9);
        if (s.size()!=0) { //不为空返回
            List<ShopType> shop = new ArrayList<>();
            for (String s1 : s) {
                ShopType shopType = JSONUtil.toBean(s1, ShopType.class);
                shop.add(shopType);
            }
            return shop;
        }
        //2.redis没有从mysql数据库查
        List<ShopType> shop = query().orderByAsc("sort").list();
        //3.数据库没有则报错
        if (shop == null) {
            return null;
        }
        //4.数据库有将数据返回给客户端，并将商家信息存在redis中
        for (ShopType shopType : shop) {
            stringRedisTemplate.opsForList().rightPush("cache:shoptype:",JSONUtil.toJsonStr(shopType));
        }

        return shop;

    }


}
