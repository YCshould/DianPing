package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 第一个拦截器，拦截所有路径，实现在未登入的时候也刷新token的有效期
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头里面的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;  //没有token的时候也放行
        }
        //基于token获取redis中的用户
        Map<Object, Object> usermap = stringRedisTemplate.opsForHash().entries("login:token"+token);
        //判断用户是否存在
        if(usermap.isEmpty()){
            return true;
        }
        //将usermap转化为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(usermap, new UserDTO(), false);
        //存在则把用户信息存在ThreadLocal中
        UserHolder.saveUser(userDTO);
        //刷新token的有效期
        stringRedisTemplate.expire("login:token"+token,30, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
