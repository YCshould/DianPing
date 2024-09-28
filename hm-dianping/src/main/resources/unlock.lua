
-- 比较线程标识与锁中的标识是否一致
-- KEYS[1]表示锁的名称
-- ARGS[1]表示锁的标识
if(redis.call('get',KEYS[1])==ARGS[1]) then
    -- 释放锁
    redis.call('del',KEYS[1])
end
return 0