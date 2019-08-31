package com.blackhold.redis.lock.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * @author jinzhihong
 * @create 2019-08-27-22:23
 */
@RestController
public class TestController {

    private final static Logger logger = LoggerFactory.getLogger(TestController.class);

    private static ExecutorService executorService = Executors.newCachedThreadPool();

    private static final int THREAD_COUNT = 2;

    @Autowired
    private RedisTemplate redisTemplate;

    @GetMapping("/test")
    public String test() throws InterruptedException {
        //设置用户user_001到 Redis,初始化账户金额为0.
        redisTemplate.opsForValue().setIfAbsent("user_001",new UserAccount("user_001",0));
        //开启10个线程进行同步测试，每个线程为账户增加1元
        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.execute(new AccountOperationThread("user_001",1));
        }
        //休眠1秒等待线程跑完
        TimeUnit.MILLISECONDS.sleep(1000);
        //获得Redis中的user_001账户
        UserAccount userAccount = (UserAccount) redisTemplate.opsForValue().get("user_001");
        logger.info("user id : " + userAccount.getUserId()  + " amount : " + userAccount.getAmount());
        return "success";
    }

}
