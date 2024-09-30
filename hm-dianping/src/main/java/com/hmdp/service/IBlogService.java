package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryblogbyid(Long id);

    Result islikeblog(Long id);

    Result querybloglikes(Long id);

    Result saveblog(Blog blog);

    Result queryblogfollow(Long max, Integer offset);
}
