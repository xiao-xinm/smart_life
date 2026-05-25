package org.javaup.service.impl;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IAutoIssueNotifyService;
import org.javaup.service.IShopService;
import org.javaup.service.IVoucherService;
import org.javaup.utils.UserHolder;
import org.javaup.vo.AutoIssueNotificationVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AutoIssueNotifyServiceImpl implements IAutoIssueNotifyService {

    @Value("${seckill.autoissue.notify.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${seckill.autoissue.notify.app.enabled:false}")
    private boolean appEnabled;

    @Value("${seckill.autoissue.notify.sms.to:}")
    private String smsTo;

    @Value("${seckill.autoissue.notify.dedup.window.seconds:300}")
    private long dedupWindowSeconds;

    @Resource
    private RedisCache redisCache;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IShopService shopService;

    @Override
    public void sendAutoIssueNotify(Long voucherId, Long userId, Long orderId) {
        try {
            if (!shouldNotify(voucherId, userId)) {
                return;
            }
            pushNotification(userId, buildClaimNotification(voucherId, userId, orderId));
            String content = String.format("voucher claimed | voucherId=%s userId=%s orderId=%s", voucherId, userId, orderId);
            if (smsEnabled && StrUtil.isNotBlank(smsTo)) {
                log.info("[VOUCHER_NOTIFY_SMS] to={} content={}", smsTo, content);
            }
            if (appEnabled) {
                log.info("[VOUCHER_NOTIFY_APP] userId={} content={}", userId, content);
            }
        } catch (Exception e) {
            log.warn("send voucher notification failed", e);
        }
    }

    @Override
    public void sendReminderNotify(Long voucherId, Long userId, LocalDateTime beginTime) {
        try {
            pushNotification(userId, buildReminderNotification(voucherId, userId, beginTime));
            String content = String.format("voucher reminder | voucherId=%s userId=%s beginTime=%s", voucherId, userId, beginTime);
            if (smsEnabled && StrUtil.isNotBlank(smsTo)) {
                log.info("[VOUCHER_REMINDER_SMS] to={} content={}", smsTo, content);
            }
            if (appEnabled) {
                log.info("[VOUCHER_REMINDER_APP] userId={} content={}", userId, content);
            }
        } catch (Exception e) {
            log.warn("send voucher reminder notification failed", e);
        }
    }

    @Override
    public List<AutoIssueNotificationVo> listNotifications(int limit) {
        Long userId = UserHolder.getUser().getId();
        return listNotificationsByUser(userId, limit, false);
    }

    @Override
    public List<AutoIssueNotificationVo> listUnreadNotifications(int limit) {
        Long userId = UserHolder.getUser().getId();
        return listNotificationsByUser(userId, limit, true);
    }

    @Override
    public void markAllRead() {
        Long userId = UserHolder.getUser().getId();
        RedisKeyBuild readKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SMART_LIFE_NOTIFY_READ_HASH_KEY, userId);
        for (AutoIssueNotificationVo notification : listNotificationsByUser(userId, 50, false)) {
            if (notification.getId() != null) {
                redisCache.putHash(readKey, notification.getId(), 1, 30, TimeUnit.DAYS);
            }
        }
    }

    private List<AutoIssueNotificationVo> listNotificationsByUser(Long userId, int limit, boolean unreadOnly) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<AutoIssueNotificationVo> notifications = redisCache.rangeForList(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SMART_LIFE_NOTIFY_LIST_KEY, userId),
                0,
                safeLimit - 1L,
                AutoIssueNotificationVo.class
        );
        if (notifications == null || notifications.isEmpty()) {
            return Collections.emptyList();
        }
        RedisKeyBuild readKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SMART_LIFE_NOTIFY_READ_HASH_KEY, userId);
        notifications.forEach(notification -> {
            Integer read = redisCache.getForHash(readKey, notification.getId(), Integer.class);
            notification.setRead(read != null && read == 1);
        });
        if (!unreadOnly) {
            return notifications;
        }
        return notifications.stream()
                .filter(notification -> !Boolean.TRUE.equals(notification.getRead()))
                .toList();
    }

    private void pushNotification(Long userId, AutoIssueNotificationVo notification) {
        RedisKeyBuild listKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SMART_LIFE_NOTIFY_LIST_KEY, userId);
        redisCache.leftPushForList(listKey, notification);
        redisCache.trimForList(listKey, 0, 49);
    }

    private AutoIssueNotificationVo buildClaimNotification(Long voucherId, Long userId, Long orderId) {
        VoucherContext context = loadVoucherContext(voucherId);
        AutoIssueNotificationVo notification = baseNotification(voucherId, userId, context);
        notification.setOrderId(orderId);
        notification.setTitle("领取成功");
        notification.setContent("你领取的 " + context.shopName + "《" + context.voucherTitle + "》已进入券包，可去店铺使用。");
        return notification;
    }

    private AutoIssueNotificationVo buildReminderNotification(Long voucherId, Long userId, LocalDateTime beginTime) {
        VoucherContext context = loadVoucherContext(voucherId);
        String timeText = beginTime == null ? "现在" : beginTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        AutoIssueNotificationVo notification = baseNotification(voucherId, userId, context);
        notification.setOrderId(null);
        notification.setTitle("开抢提醒");
        notification.setContent(context.shopName + "《" + context.voucherTitle + "》将在 " + timeText + " 开抢，可以去店铺抢券。");
        return notification;
    }

    private AutoIssueNotificationVo baseNotification(Long voucherId, Long userId, VoucherContext context) {
        AutoIssueNotificationVo notification = new AutoIssueNotificationVo();
        notification.setId(UUID.randomUUID().toString());
        notification.setUserId(userId);
        notification.setVoucherId(voucherId);
        notification.setShopId(context.shopId);
        notification.setShopName(context.shopName);
        notification.setVoucherTitle(context.voucherTitle);
        notification.setRead(false);
        notification.setCreateTime(LocalDateTime.now());
        return notification;
    }

    private VoucherContext loadVoucherContext(Long voucherId) {
        Voucher voucher = voucherService.getById(voucherId);
        Shop shop = null;
        if (voucher != null && voucher.getShopId() != null) {
            shop = shopService.getById(voucher.getShopId());
        }
        return new VoucherContext(
                voucher == null ? null : voucher.getShopId(),
                shop == null ? "你订阅的店铺" : shop.getName(),
                voucher == null ? "优惠券" : voucher.getTitle()
        );
    }

    private boolean shouldNotify(Long voucherId, Long userId) {
        try {
            return redisCache.setIfAbsent(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_AUTO_ISSUE_NOTIFY_DEDUP_KEY, voucherId, userId),
                    "1",
                    dedupWindowSeconds,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            return true;
        }
    }

    private record VoucherContext(Long shopId, String shopName, String voucherTitle) {
    }
}
