package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;  //主要是为拦截器类注入stringRedisTemplate

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登入拦截器
        registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(  //手机端的大多不用拦截不同于管理系统页面
                "/user/code",  //发送验证码不用拦截
                "/user/login",  //登入不用拦截
                "/blog/hot",  //首页热卖不用拦截
                "/shop/**",
                "/shop-type/**",
                "/upload/**",
                "/voucher/**"
        ).order(1);
        //刷新拦截器
        registry.addInterceptor(new RefreshInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
    /**
     * order(0)表示拦截器的先后执行顺序，越小越先执行
     */
}
