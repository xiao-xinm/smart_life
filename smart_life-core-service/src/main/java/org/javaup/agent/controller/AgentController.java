package org.javaup.agent.controller;

import jakarta.annotation.Resource;
import org.javaup.agent.dto.AgentChatRequest;
import org.javaup.agent.dto.AgentChatResponse;
import org.javaup.agent.dto.AgentRecommendation;
import org.javaup.agent.dto.AgentToolRequest;
import org.javaup.agent.dto.AgentVectorDoc;
import org.javaup.agent.dto.AgentVoucherView;
import org.javaup.agent.service.AgentGuideService;
import org.javaup.dto.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/agent")
public class AgentController {

    @Resource
    private AgentGuideService agentGuideService;

    @PostMapping("/chat")
    public Result<AgentChatResponse> chat(
            @RequestBody AgentChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return Result.ok(agentGuideService.chat(request, authorization));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestBody AgentChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return agentGuideService.streamChat(request, authorization);
    }

    @PostMapping("/tools/search-shops")
    public Result<List<AgentRecommendation>> searchShops(@RequestBody AgentToolRequest request) {
        return Result.ok(agentGuideService.searchShops(request));
    }

    @GetMapping("/tools/rag/vector-docs")
    public Result<List<AgentVectorDoc>> listVectorDocs() {
        return Result.ok(agentGuideService.listVectorDocs());
    }

    @GetMapping("/tools/shop/{shopId}/vouchers")
    public Result<List<AgentVoucherView>> queryShopVouchers(@PathVariable("shopId") Long shopId) {
        return Result.ok(agentGuideService.queryShopVouchers(shopId));
    }

    @PostMapping("/tools/voucher/{voucherId}/subscribe")
    public Result<Integer> subscribeVoucher(@PathVariable("voucherId") Long voucherId) {
        return Result.ok(agentGuideService.subscribeVoucher(voucherId));
    }

    @GetMapping("/tools/voucher/{voucherId}/subscribe/status")
    public Result<Integer> getSubscribeStatus(@PathVariable("voucherId") Long voucherId) {
        return Result.ok(agentGuideService.getSubscribeStatus(voucherId));
    }
}
