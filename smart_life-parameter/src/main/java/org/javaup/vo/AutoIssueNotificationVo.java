package org.javaup.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AutoIssueNotificationVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;

    private Long userId;

    private Long voucherId;

    private Long shopId;

    private String shopName;

    private String voucherTitle;

    private Long orderId;

    private String title;

    private String content;

    private Boolean read;

    private LocalDateTime createTime;
}
