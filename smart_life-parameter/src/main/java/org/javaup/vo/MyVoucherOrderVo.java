package org.javaup.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class MyVoucherOrderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long orderId;

    private Long voucherId;

    private Long shopId;

    private String shopName;

    private String shopArea;

    private String shopAddress;

    private String voucherTitle;

    private String voucherSubTitle;

    private String voucherRules;

    private Long payValue;

    private Long actualValue;

    private Integer orderStatus;

    private String orderStatusText;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private LocalDateTime useTime;
}
