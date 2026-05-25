package org.javaup.service;

import org.javaup.vo.AutoIssueNotificationVo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 优惠券提醒与领取结果通知服务接口
 * @author: 阿星不是程序员
 **/
public interface IAutoIssueNotifyService {
    
    void sendAutoIssueNotify(Long voucherId, Long userId, Long orderId);

    void sendReminderNotify(Long voucherId, Long userId, LocalDateTime beginTime);

    List<AutoIssueNotificationVo> listNotifications(int limit);

    List<AutoIssueNotificationVo> listUnreadNotifications(int limit);

    void markAllRead();
}
