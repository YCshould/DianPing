package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.PageUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result setcode(String phone, HttpSession session) {
        //手机号验证，工具栏中有手机号正则表达式方法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号错误");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //将验证码存在session中
        //session.setAttribute("code",code);
        //将验证码存在redis中
        stringRedisTemplate.opsForValue().set("login:code:"+phone,code,2, TimeUnit.MINUTES);  //设计验证码有效时间为二分钟
        //发送验证码
        //调用第三发平台暂时不弄
        log.info("发送验证码成功:{}",code);

        return Result.ok();
    }

    /**
     * 登入
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号错误");
        }
        //2.校验验证码 从redis中获取验证码
        String cachecode = stringRedisTemplate.opsForValue().get("login:code:"+phone);
        String code = loginForm.getCode();
        if(cachecode==null||!cachecode.equals(code)){
            return Result.fail("验证码错误");
        }
        //3.查询用户信息,该类继承了ServiceImpl(这个类是继承mybatis的可以对单表进行操作)
        User user = query().eq("phone", phone).one();

        //4.不存在用户则新建用户
        if(user==null){
            log.info("不存在用户则新建用户");
            user=createuser(phone);
        }
        //5.存在就将用户信息保存在redis
        //5.1.随机生成token作为登入令牌
        String token = UUID.randomUUID().toString(true);
        //5.2.因为将用户信息以哈希的形式存入到redis中效果更好，所以要将用户信息转化为hashmap结构
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                    .setIgnoreNullValue(true)
                    .setFieldValueEditor((fieldName,fieldVaule)->fieldVaule.toString()));
        stringRedisTemplate.opsForHash().putAll("login:token"+token,stringObjectMap);
        //设计token有效期
        stringRedisTemplate.expire("login:token"+token,30,TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }

    private User createuser(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName("user"+RandomUtil.randomString(5));
        //保存用户信息,该类继承了ServiceImpl(这个类是继承mybatis的可以对单表进行操作)
        save(user);
        return user;
    }
}
