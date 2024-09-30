package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService iFollowService;

    @PutMapping("/{id}/{isfollow}")
    public Result follow(@PathVariable Long id,@PathVariable Boolean isfollow){
        return iFollowService.follow(id,isfollow);
    }


    @GetMapping("or/not/{id}")
    public Result follow(@PathVariable Long id){
        return iFollowService.isfollow(id);
    }

    @GetMapping("/common/{id}")
    public Result followcommon(@PathVariable Long id){
        return iFollowService.followcommon(id);
    }
}
