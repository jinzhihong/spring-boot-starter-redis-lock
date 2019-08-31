package com.blackhold.redis.lock.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import sun.tools.tree.NullExpression;

import javax.validation.constraints.Null;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 账户操作
 * 多线程方式模拟分布式环境
 *
 * @author jinzhihong
 * @create 2019-08-27-22:58
 */
public class AccountOperationThread implements Runnable {

    private final static Logger logger = LoggerFactory.getLogger(AccountOperationThread.class);

    //操作金额
    private int amount;

    private String userId;


    private RedisTemplate redisTemplate;

    //守护线程
    private DaemonThread daemonThread;

    public AccountOperationThread(String userId, int amount) {
        this.amount = amount;
        this.userId = userId;
        redisTemplate = (RedisTemplate) SpringBeanUtil.getBeanByName("redisTemplate");
    }

    @Override
    public void run() {
        deamonRedisLock();
    }


    /**
     * 不做任何同步（锁）
     */
    private void noLock() {
        Random random = new Random();
        try {
            // 为了更好测试，模拟线程进入间隔，每个线程随机休眠 1-100毫秒再进行业务操作
            TimeUnit.MILLISECONDS.sleep(random.nextInt(100) + 1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //模拟数据库中获取用户账号
        UserAccount userAccount = (UserAccount) redisTemplate.opsForValue().get(userId);
        //设置金额
        userAccount.setAmount(userAccount.getAmount() + amount);
        logger.info(Thread.currentThread().getName() + " : user id : " + userId + " amount : " + userAccount.getAmount());
        //模拟存回数据库
        redisTemplate.opsForValue().set(userId, userAccount);
    }

    /**
     * 1.抢占资源时判断是否被锁。
     * 2.如未锁则抢占成功且加锁，否则等待锁释放。
     * 3.业务完成后释放锁,让给其它线程。
     */
    private void redisLock() {
        Random random = new Random();
        try {
            //为了更好测试，模拟线程进入间隔，每个线程随机休眠 1-1000毫秒再进行业务操作
            TimeUnit.MILLISECONDS.sleep(random.nextInt(1000) + 1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        while (true) {
            Object lock = redisTemplate.opsForValue().get(userId + ":syn");
            if (lock == null) {
                // 获得锁 -> 加锁 -> 跳出循环
                logger.info(Thread.currentThread().getName() + ":获得锁");
                redisTemplate.opsForValue().set(userId + ":syn", "lock");
                break;
            }
            try {
                // 等待500毫秒重试获得锁
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        try {
            //模拟数据库中获取用户账号
            UserAccount userAccount = (UserAccount) redisTemplate.opsForValue().get(userId);
            //设置金额
            userAccount.setAmount(userAccount.getAmount() + amount);
            logger.info(Thread.currentThread().getName() + " : user id : " + userId + " amount : " + userAccount.getAmount());
            //模拟存回数据库
            redisTemplate.opsForValue().set(userId, userAccount);
        } finally {
            //释放锁
            redisTemplate.delete(userId + ":syn");
            logger.info(Thread.currentThread().getName() + ":释放锁");
        }
    }


    /**
     * 1.原子操作加锁
     * 2.竞争线程循环重试获得锁
     * 3.业务完成释放锁
     */
    private void atomicityRedisLock() {
        //Spring data redis 支持的原子性操作
        while (!redisTemplate.opsForValue().setIfAbsent(userId + ":syn", "lock")) {
            try {
                // 等待100毫秒重试获得锁
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        logger.info(Thread.currentThread().getName() + ":获得锁");
        try {
            Thread.currentThread().interrupt();
            //模拟数据库中获取用户账号
            UserAccount userAccount = (UserAccount) redisTemplate.opsForValue().get(userId);
            //设置金额
            userAccount.setAmount(userAccount.getAmount() + amount);
            logger.info(Thread.currentThread().getName() + " : user id : " + userId + " amount : " + userAccount.getAmount());
            //模拟存回数据库
            redisTemplate.opsForValue().set(userId, userAccount);
        } finally {
            //释放锁
            redisTemplate.delete(userId + ":syn");
            logger.info(Thread.currentThread().getName() + ":释放锁");
        }
    }


    /**
     * 1. 线程1 拿到锁
     * 2. 业务执行到一半宕机
     * 3. 无法正常释放锁
     * 4. 其它线程竞争资源导致死锁
     */
    private void deadLock() {
        //Spring data redis 支持的原子性操作
        while (!redisTemplate.opsForValue().setIfAbsent(userId + ":syn", "lock")) {
            try {
                // 等待100毫秒重试获得锁
                logger.info(Thread.currentThread().getName() + ":尝试循环获取锁");
                TimeUnit.MILLISECONDS.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        logger.info(Thread.currentThread().getName() + ":获得锁");
        try {
            // 应用在这里宕机，进程退出，无法执行 finally;
            Thread.currentThread().interrupt();
            // 业务逻辑...
        } finally {
            //释放锁
            if (!Thread.currentThread().isInterrupted()) {
                redisTemplate.delete(userId + ":syn");
                logger.info(Thread.currentThread().getName() + ":释放锁");
            }
        }
    }


    /**
     * 1. 原子操作，获得锁并设置5秒过期时间
     * 2. 业务执行到一半宕机
     * 3. 无法正常释放锁
     * 4. 其它线程等待过期时间获得锁
     */
    private void atomicityAndExRedisLock() {
        try {
            //Spring data redis 支持的原子性操作,并设置5秒过期时间
            while (!redisTemplate.opsForValue().setIfAbsent(userId + ":syn", System.currentTimeMillis() + 5000, 5000, TimeUnit.MILLISECONDS)) {
                // 等待100毫秒重试获得锁
                logger.info(Thread.currentThread().getName() + ":尝试循环获取锁");
                TimeUnit.MILLISECONDS.sleep(1000);
            }
            logger.info(Thread.currentThread().getName() + ":获得锁");
            // 应用在这里宕机，进程退出，无法执行 finally;
            Thread.currentThread().interrupt();
            // 业务逻辑...
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            //释放锁
            if (!Thread.currentThread().isInterrupted()) {
                redisTemplate.delete(userId + ":syn");
                logger.info(Thread.currentThread().getName() + ":释放锁");
            }
        }
    }


    /**
     * 1. 原子操作，获得锁并设置5秒过期时间
     * 2. 获取锁并且开启守护线程
     * 3. 业务执行时间超过5秒
     * 4. 守护线程判断时间，快超时时为锁续命5秒
     * 5. 主线程执行完毕，释放锁，释放守护线程
     */
    private void deamonRedisLock() {
        //Spring data redis 支持的原子性操作,并设置5秒过期时间
        String uuid = UUID.randomUUID().toString();
        String value = Thread.currentThread().getId() + ":" + uuid;
        try {
            while (!redisTemplate.opsForValue().setIfAbsent(userId + ":syn", value, 5000, TimeUnit.MILLISECONDS)) {
                // 等待100毫秒重试获得锁
                logger.info(Thread.currentThread().getName() + ":尝试循环获取锁");
                TimeUnit.MILLISECONDS.sleep(1000);
            }
            logger.info(Thread.currentThread().getName() + ":获得锁");
            //开启守护线程
            daemonThread = new DaemonThread(userId + ":syn");
            Thread thread = new Thread(daemonThread);
            thread.start();
            // 业务逻辑执行10秒...
            TimeUnit.MILLISECONDS.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            //释放锁 这里也需要原子操作,今后通过 Redis + Lua 讲
            String result = (String)redisTemplate.opsForValue().get(userId + ":syn");
            if(value.equals(result)) {
                redisTemplate.delete(userId + ":syn");
                logger.info(Thread.currentThread().getName() + ":释放锁");
            }
            //关闭守护线程
            daemonThread.stop();
        }
    }




}
