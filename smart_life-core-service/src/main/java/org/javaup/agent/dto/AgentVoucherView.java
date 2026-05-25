package org.javaup.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentVoucherView {

    private Long voucherId;

    private String title;

    private String subTitle;

    private String rules;

    private Long payValue;

    private Long actualValue;

    private Integer type;

    private Integer status;

    private Integer stock;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;
}
