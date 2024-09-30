package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryblogbyid(Long id) {
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("评论不存在");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        //查询是否被点赞
        //获取登入用户
        Long userid = UserHolder.getUser().getId();
        //是否已经点赞，通过Set集合中判断
        String key="blog:like:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userid.toString());
        blog.setIsLike(score!=null);

        return Result.ok(blog);
    }

    /**
     * 是否点赞过
     * @param id
     * @return
     */
    @Override
    public Result islikeblog(Long id) {
        //获取登入用户
        Long userid = UserHolder.getUser().getId();
        //是否已经点赞，通过Set集合中判断
        String key="blog:like:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userid.toString());

        if (score==null) {
            //如果没有点赞，数据库点赞数加一，redis中set集合加入点赞用户id 用set无序后续不好做显示最新点赞的5位用户
            //此时用zset更合适，zset中每个元素有score权重，查找元素的score是否存在也可以实现set中的ismember,用时间戳currentTimeMillis做权重
            boolean update = update().setSql("liked=liked+1").eq("id", id).update();
            if(update){
                stringRedisTemplate.opsForZSet().add(key,userid.toString(),System.currentTimeMillis());
            }
        }else {
            boolean update = update().setSql("liked=liked-1").eq("id", id).update();
            if(update){
                stringRedisTemplate.opsForZSet().remove(key,userid.toString());
            }
            //如果已经点赞，数据库点赞数减一，redis中set集合删除取消点赞用户id
        }
        return Result.ok();
    }

    /**
     * 查询top5点赞用户
     * @param id
     * @return
     */
    @Override
    public Result querybloglikes(Long id) {
        Long userid = UserHolder.getUser().getId();
        String key="blog:like:"+id;
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(range==null||range.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析出用户id
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> collect = userService.listByIds(ids).stream().map(
                user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(collect);
    }

    @Override
    public Result saveblog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        boolean save = save(blog);
        if(!save){
            return Result.fail("发布blog失败");
        }
        //查询笔记作者的粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送作品id给粉丝
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            String key="feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());  //收件箱
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryblogfollow(Long max, Integer offset) {
        //获取当前用户
        Long userid = UserHolder.getUser().getId();
        //查询收件箱
        String key="feed:"+userid;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //解析数据，blogid,mintime(最小时间戳)，offset
        List<Long> ids=new ArrayList<>(typedTuples.size());
        int os=1; //记录最小时间戳相同的有几个
        long minTime=0;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //比如时间戳5 4 4 2 3，第一轮时间戳5 4 4的blogid会被保存，当4有二个,offset为2，就从第一个4开始偏移2个单位，从2的位置开始下一次读(2作为下一次的max)
            ids.add(Long.valueOf(tuple.getValue()));
            long time=tuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else {
                minTime=time;
                os=1;
            }

        }
        //查询blog
        //得到id字符串
        String idstr= StrUtil.join(",",ids);
        //mybatis按顺序查询
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id,"+idstr+")").list();

        for (Blog blog : blogs) {
            //查询blog有关用户信息
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //是否已经点赞，通过Set集合中判断
            String keyblog="blog:like:"+blog.getId();
            Double score = stringRedisTemplate.opsForZSet().score(keyblog, userid.toString());
            blog.setIsLike(score!=null);
        }
        ScrollResult scrollResult=new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
