package com.blackhold.redis.lock.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.TimeUnit;

/**
 * 守护线程
 *
 * @author jinzhihong
 * @create 2019-08-30-11:47
 */
public class DaemonThread implements Runnable {
    private final static Logger logger = LoggerFactory.getLogger(DaemonThread.class);

    private boolean daemon = true;

    private String lockKey;


    private RedisTemplate redisTemplate;


    public DaemonThread(String lockKey) {
        this.lockKey = lockKey;
        redisTemplate = (RedisTemplate) SpringBeanUtil.getBeanByName("redisTemplate");
    }





    @Override
    public void run() {
        try {
            while (daemon) {
                long time = redisTemplate.getExpire(lockKey,TimeUnit.MILLISECONDS);
                // 剩余有效期小于1秒则续命
                if(time < 1000) {
                    logger.info("守护进程: " + Thread.currentThread().getName() + " 延长锁时间 5000 毫秒" );
                    redisTemplate.expire(lockKey , 5000, TimeUnit.MILLISECONDS);
                }
                TimeUnit.MILLISECONDS.sleep(300);
            }
            logger.info(" 守护进程: " + Thread.currentThread().getName() + "关闭 ");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        daemon = false;
    }
}

