package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    /**
     * 关注与取关
     * @param id
     * @param isfollow
     * @return
     */

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService iUserService;

    @Override
    public Result follow(Long id, Boolean isfollow) {
        Long userid = UserHolder.getUser().getId();
        String key="follows:"+userid;
        //关注数据库插入
        if (isfollow) {
            Follow follow=new Follow();
            follow.setUserId(userid);
            follow.setFollowUserId(id);
            boolean issave = save(follow);
            if(issave){

                stringRedisTemplate.opsForSet().add(key,id.toString());
            }
        }else{
            //取关数据库删除
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userid).eq("follow_user_id", id));
            if(remove){
                stringRedisTemplate.opsForSet().remove(key,id.toString());
            }

        }

        return Result.ok();
    }

    /**
     * 查询是否关注
     * @param id
     * @return
     */
    @Override
    public Result isfollow(Long id) {
        Long userid = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userid).eq("follow_user_id", id).count();
        return Result.ok(count>0);
    }

    /**
     * 从redis的set集合中看二个用户是否有共同关注的用户，交集
     * @param id
     * @return
     */
    @Override
    public Result followcommon(Long id) {
        Long userid = UserHolder.getUser().getId();
        String key1="follows:"+userid;
        String key2="follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = iUserService.listByIds(collect);
        List<UserDTO> userDTOList = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
