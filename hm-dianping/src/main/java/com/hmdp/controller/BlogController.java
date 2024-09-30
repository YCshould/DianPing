package com.hmdp.controller;


import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveblog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {

        return blogService.islikeblog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //查询是否被点赞
            //获取登入用户
            UserDTO user1 = UserHolder.getUser();
            if(user1==null){
                //用户未登入无需查询是否点赞
                return;
            }
            Long userid = UserHolder.getUser().getId();
            //是否已经点赞，通过Set集合中判断
            String key="blog:like:"+blog.getId();
            Double score = stringRedisTemplate.opsForZSet().score(key, userid.toString());
            blog.setIsLike(score!=null);
        });
        return Result.ok(records);
    }

    @GetMapping("/{id}")
    public Result queryblogbyid(@PathVariable Long id){

        return blogService.queryblogbyid(id);
    }

    @GetMapping("/likes/{id}")
    public Result querybloglikes(@PathVariable Long id){

        return blogService.querybloglikes(id);
    }
    //current默认值1,从第一页开始查
    @GetMapping("/of/user")
    public Result queryBlogById(@RequestParam("id") Long id,@RequestParam(value = "current",defaultValue = "1") Integer current){
        Page<Blog> page = blogService.query().eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    public Result queryblogfollow(@RequestParam("lastId") Long max,@RequestParam(value = "offset",defaultValue = "0") Integer offset){
        return blogService.queryblogfollow(max,offset);
    }
}
