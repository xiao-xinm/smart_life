package org.javaup.agent.service;

import org.javaup.agent.dto.AgentChatRequest;
import org.javaup.agent.dto.AgentChatResponse;
import org.javaup.agent.dto.AgentRecommendation;
import org.javaup.agent.dto.AgentToolRequest;
import org.javaup.agent.dto.AgentVoucherView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface AgentGuideService {

    AgentChatResponse chat(AgentChatRequest request, String authorization);

    SseEmitter streamChat(AgentChatRequest request, String authorization);

    List<AgentRecommendation> searchShops(AgentToolRequest request);

    List<AgentVoucherView> queryShopVouchers(Long shopId);

    Integer subscribeVoucher(Long voucherId);

    Integer getSubscribeStatus(Long voucherId);
}
