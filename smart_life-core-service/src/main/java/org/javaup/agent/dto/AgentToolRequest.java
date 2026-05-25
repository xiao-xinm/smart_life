package org.javaup.agent.dto;

import lombok.Data;

@Data
public class AgentToolRequest {

    private String keyword;

    private Integer typeId;

    private Integer current = 1;

    private Long maxAvgPrice;

    private Boolean requireVoucher;

    private Long shopId;

    private Long voucherId;
}
