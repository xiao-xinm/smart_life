package org.javaup.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class VoucherSubscribeCenterVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long voucherId;

    private Long shopId;

    private String shopName;

    private String shopArea;

    private String shopAddress;

    private String shopImages;

    private String voucherTitle;

    private String voucherSubTitle;

    private String voucherRules;

    private Long payValue;

    private Long actualValue;

    private Integer subscribeStatus;

    private Long orderId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
