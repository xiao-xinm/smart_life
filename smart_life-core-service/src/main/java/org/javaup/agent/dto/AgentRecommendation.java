package org.javaup.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentRecommendation {

    private Long shopId;

    private String shopName;

    private String area;

    private String address;

    private Long avgPrice;

    private Integer sold;

    private Integer comments;

    private Integer score;

    private String openHours;

    private String images;

    private Double distance;

    private String reputationSummary;

    private List<String> scenarioTags = new ArrayList<>();

    private List<String> couponHighlights = new ArrayList<>();

    private String reason;

    private List<AgentVoucherView> vouchers = new ArrayList<>();
}
