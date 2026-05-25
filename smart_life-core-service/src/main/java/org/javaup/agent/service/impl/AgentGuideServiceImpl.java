package org.javaup.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.agent.dto.AgentChatRequest;
import org.javaup.agent.dto.AgentChatResponse;
import org.javaup.agent.dto.AgentRecommendation;
import org.javaup.agent.dto.AgentToolRequest;
import org.javaup.agent.dto.AgentVoucherView;
import org.javaup.agent.service.AgentGuideService;
import org.javaup.dto.VoucherSubscribeDto;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.service.IShopService;
import org.javaup.service.IVoucherService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AgentGuideServiceImpl implements AgentGuideService {

    private static final Pattern BUDGET_PATTERN = Pattern.compile("(?:人均|预算|每人|单人)?\\s*(\\d{2,4})\\s*(?:元|块|左右|以内|以下)?");

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Value("${agent.python.enabled:false}")
    private boolean pythonAgentEnabled;

    @Value("${agent.python.chat-url:http://localhost:8000/chat}")
    private String pythonAgentChatUrl;

    @Value("${agent.python.stream-url:http://localhost:8000/chat/stream}")
    private String pythonAgentStreamUrl;

    private final RestTemplate restTemplate = createRestTemplate();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AgentChatResponse chat(AgentChatRequest request, String authorization) {
        log.info("Smart Life Agent chat received, pythonAgentEnabled={}, pythonAgentChatUrl={}", pythonAgentEnabled, pythonAgentChatUrl);
        if (pythonAgentEnabled) {
            AgentChatResponse pythonResponse = callPythonAgent(request, authorization);
            if (pythonResponse != null) {
                log.info("Smart Life Agent using Python response");
                return pythonResponse;
            }
            log.info("Smart Life Agent Python response unavailable, falling back to local guide");
        } else {
            log.info("Smart Life Agent Python integration disabled, using local guide");
        }
        return localGuide(request);
    }

    @Override
    public SseEmitter streamChat(AgentChatRequest request, String authorization) {
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> {
            if (!pythonAgentEnabled) {
                sendLocalStreamResponse(emitter, request);
                return;
            }
            try {
                forwardPythonStream(request, authorization, emitter);
            } catch (Exception ex) {
                log.warn("Smart Life Agent failed to stream Python agent: {}", ex.getMessage());
                sendLocalStreamResponse(emitter, request);
            }
        });
        return emitter;
    }

    @Override
    public List<AgentRecommendation> searchShops(AgentToolRequest request) {
        String keyword = request == null ? null : request.getKeyword();
        Integer typeId = request == null ? null : request.getTypeId();
        Integer current = request == null || request.getCurrent() == null ? 1 : request.getCurrent();
        Long maxAvgPrice = request == null ? null : request.getMaxAvgPrice();
        boolean requireVoucher = request != null && Boolean.TRUE.equals(request.getRequireVoucher());
        if (requireVoucher) {
            return searchVoucherBackedShops(keyword, typeId, maxAvgPrice);
        }
        Page<Shop> page = new Page<>(current, 3);
        List<Shop> shops = shopService.query()
                .like(keyword != null && !keyword.isBlank(), "name", keyword)
                .eq(typeId != null, "type_id", typeId)
                .le(maxAvgPrice != null, "avg_price", maxAvgPrice)
                .orderByDesc("score")
                .orderByDesc("sold")
                .page(page)
                .getRecords();
        return shops.stream().map(this::toRecommendation).toList();
    }

    @Override
    public List<AgentVoucherView> queryShopVouchers(Long shopId) {
        if (shopId == null) {
            return List.of();
        }
        List<Voucher> vouchers = voucherService.query()
                .eq("shop_id", shopId)
                .eq("status", 1)
                .last("limit 5")
                .list();
        return vouchers.stream().map(this::toVoucherView).toList();
    }

    private List<AgentRecommendation> searchVoucherBackedShops(String keyword, Integer typeId, Long maxAvgPrice) {
        List<Voucher> vouchers = voucherService.query()
                .eq("status", 1)
                .last("limit 50")
                .list();
        if (vouchers.isEmpty()) {
            return List.of();
        }
        List<Long> shopIds = vouchers.stream()
                .map(Voucher::getShopId)
                .distinct()
                .toList();
        List<Shop> shops = shopService.query()
                .in("id", shopIds)
                .like(keyword != null && !keyword.isBlank(), "name", keyword)
                .eq(typeId != null, "type_id", typeId)
                .le(maxAvgPrice != null, "avg_price", maxAvgPrice)
                .orderByDesc("score")
                .orderByDesc("sold")
                .last("limit 3")
                .list();
        return shops.stream()
                .map(this::toRecommendation)
                .peek(recommendation -> {
                    recommendation.setVouchers(queryShopVouchers(recommendation.getShopId()));
                    recommendation.setCouponHighlights(buildCouponHighlights(recommendation.getVouchers()));
                })
                .toList();
    }

    @Override
    public Integer subscribeVoucher(Long voucherId) {
        VoucherSubscribeDto dto = new VoucherSubscribeDto();
        dto.setVoucherId(voucherId);
        voucherService.subscribe(dto);
        return voucherService.getSubscribeStatus(dto);
    }

    @Override
    public Integer getSubscribeStatus(Long voucherId) {
        VoucherSubscribeDto dto = new VoucherSubscribeDto();
        dto.setVoucherId(voucherId);
        return voucherService.getSubscribeStatus(dto);
    }

    private AgentChatResponse callPythonAgent(AgentChatRequest request, String authorization) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
            return restTemplate.postForObject(
                    pythonAgentChatUrl,
                    new HttpEntity<>(request, headers),
                    AgentChatResponse.class
            );
        } catch (RestClientException ex) {
            log.warn("Smart Life Agent failed to call Python agent: {}", ex.getMessage());
            return null;
        }
    }

    private void forwardPythonStream(AgentChatRequest request, String authorization, SseEmitter emitter) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(pythonAgentStreamUrl).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(300000);
        connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        connection.setRequestProperty(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        if (authorization != null && !authorization.isBlank()) {
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, authorization);
        }
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(objectMapper.writeValueAsBytes(request));
        }
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new RestClientException("Python stream returned status " + status);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String eventName = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (!data.isEmpty()) {
                        emitter.send(SseEmitter.event().name(eventName).data(data.toString()));
                        data.setLength(0);
                    }
                    eventName = "message";
                } else if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }
        emitter.complete();
    }

    private void sendLocalStreamResponse(SseEmitter emitter, AgentChatRequest request) {
        try {
            emitter.send(SseEmitter.event().name("final").data(localGuide(request)));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(90000);
        return new RestTemplate(factory);
    }

    private AgentChatResponse localGuide(AgentChatRequest request) {
        String message = request == null || request.getMessage() == null ? "" : request.getMessage();
        AgentToolRequest toolRequest = new AgentToolRequest();
        toolRequest.setKeyword(extractKeyword(message));
        toolRequest.setTypeId(inferTypeId(message));
        toolRequest.setMaxAvgPrice(extractBudget(message));
        toolRequest.setRequireVoucher(wantsVoucher(message));

        List<AgentRecommendation> recommendations = new ArrayList<>(searchShops(toolRequest));
        for (AgentRecommendation recommendation : recommendations) {
            if (recommendation.getVouchers().isEmpty()) {
                recommendation.setVouchers(queryShopVouchers(recommendation.getShopId()));
            }
            recommendation.setCouponHighlights(buildCouponHighlights(recommendation.getVouchers()));
            recommendation.setReason(buildReason(message, recommendation));
        }

        AgentChatResponse response = new AgentChatResponse();
        response.setRecommendations(recommendations);
        response.getToolTrace().add("search_shops");
        response.getToolTrace().add("query_shop_vouchers");
        if (toolRequest.getMaxAvgPrice() != null) {
            response.getToolTrace().add("filter_budget:" + toolRequest.getMaxAvgPrice());
        }
        if (Boolean.TRUE.equals(toolRequest.getRequireVoucher())) {
            response.getToolTrace().add("filter_has_voucher");
        }
        response.setAnswer(buildAnswer(message, recommendations));
        response.getSuggestions().add("帮我只看有优惠券的店");
        response.getSuggestions().add("人均 100 以内的美食店");
        response.getSuggestions().add("给我推荐适合今晚去的店");
        response.getSuggestions().add("这张券售罄时帮我订阅提醒");
        return response;
    }

    private AgentRecommendation toRecommendation(Shop shop) {
        AgentRecommendation recommendation = new AgentRecommendation();
        recommendation.setShopId(shop.getId());
        recommendation.setShopName(shop.getName());
        recommendation.setArea(shop.getArea());
        recommendation.setAddress(shop.getAddress());
        recommendation.setAvgPrice(shop.getAvgPrice());
        recommendation.setSold(shop.getSold());
        recommendation.setComments(shop.getComments());
        recommendation.setScore(shop.getScore());
        recommendation.setOpenHours(shop.getOpenHours());
        recommendation.setImages(shop.getImages());
        recommendation.setDistance(shop.getDistance());
        recommendation.setReputationSummary(buildReputationSummary(shop));
        recommendation.setScenarioTags(buildScenarioTags(shop));
        return recommendation;
    }

    private AgentVoucherView toVoucherView(Voucher voucher) {
        AgentVoucherView view = new AgentVoucherView();
        view.setVoucherId(voucher.getId());
        view.setTitle(voucher.getTitle());
        view.setSubTitle(voucher.getSubTitle());
        view.setRules(voucher.getRules());
        view.setPayValue(voucher.getPayValue());
        view.setActualValue(voucher.getActualValue());
        view.setType(voucher.getType());
        view.setStatus(voucher.getStatus());
        view.setStock(voucher.getStock());
        view.setBeginTime(voucher.getBeginTime());
        view.setEndTime(voucher.getEndTime());
        return view;
    }

    private String extractKeyword(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("火锅")) {
            return "火锅";
        }
        if (lower.contains("ktv")) {
            return "KTV";
        }
        if (lower.contains("酒吧")) {
            return "酒吧";
        }
        if (lower.contains("按摩") || lower.contains("spa")) {
            return "SPA";
        }
        if (lower.contains("美食") || lower.contains("吃") || lower.contains("饭")) {
            return null;
        }
        return null;
    }

    private Long extractBudget(String message) {
        Matcher matcher = BUDGET_PATTERN.matcher(message);
        Long budget = null;
        while (matcher.find()) {
            long candidate = Long.parseLong(matcher.group(1));
            if (candidate >= 20 && candidate <= 1000) {
                budget = candidate;
            }
        }
        return budget;
    }

    private boolean wantsVoucher(String message) {
        return message.contains("优惠")
                || message.contains("券")
                || message.contains("便宜")
                || message.contains("折扣")
                || message.contains("划算");
    }

    private Integer inferTypeId(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("美食") || lower.contains("吃") || lower.contains("饭") || lower.contains("火锅")) {
            return 1;
        }
        if (lower.contains("ktv")) {
            return 6;
        }
        if (lower.contains("酒吧")) {
            return 7;
        }
        return null;
    }

    private String buildReason(String message, AgentRecommendation recommendation) {
        List<String> parts = new ArrayList<>();
        if (recommendation.getScore() != null) {
            parts.add("评分 " + recommendation.getScore() / 10.0);
        }
        if (recommendation.getAvgPrice() != null) {
            parts.add("人均约 " + recommendation.getAvgPrice() + " 元");
        }
        Long budget = extractBudget(message);
        if (budget != null && recommendation.getAvgPrice() != null && recommendation.getAvgPrice() <= budget) {
            parts.add("符合你的预算");
        }
        if (!recommendation.getVouchers().isEmpty()) {
            parts.add("当前有可用优惠券");
        }
        if (message.contains("附近") && recommendation.getDistance() != null) {
            parts.add("距离较近");
        }
        return parts.isEmpty() ? "匹配你的本地生活需求，适合作为备选方案。" : String.join("，", parts) + "。";
    }

    private String buildReputationSummary(Shop shop) {
        List<String> parts = new ArrayList<>();
        if (shop.getScore() != null) {
            parts.add("评分 " + shop.getScore() / 10.0);
        }
        if (shop.getComments() != null) {
            parts.add(shop.getComments() + " 条评论");
        }
        if (shop.getSold() != null) {
            parts.add("销量 " + shop.getSold());
        }
        if (parts.isEmpty()) {
            return "暂无足够口碑数据。";
        }
        return String.join("，", parts) + "。";
    }

    private List<String> buildScenarioTags(Shop shop) {
        List<String> tags = new ArrayList<>();
        if (shop.getScore() != null && shop.getScore() >= 40) {
            tags.add("高评分");
        }
        if (shop.getSold() != null && shop.getSold() >= 1000) {
            tags.add("人气店");
        }
        if (shop.getComments() != null && shop.getComments() >= 1000) {
            tags.add("评论多");
        }
        if (shop.getAvgPrice() != null && shop.getAvgPrice() <= 100) {
            tags.add("预算友好");
        }
        if (shop.getOpenHours() != null && shop.getOpenHours().contains("22")) {
            tags.add("适合晚餐");
        }
        return tags;
    }

    private List<String> buildCouponHighlights(List<AgentVoucherView> vouchers) {
        List<String> highlights = new ArrayList<>();
        for (AgentVoucherView voucher : vouchers) {
            if (voucher.getPayValue() != null && voucher.getActualValue() != null) {
                long saved = voucher.getActualValue() - voucher.getPayValue();
                if (saved > 0) {
                    highlights.add("立省 " + saved + " 元");
                }
            }
            if (voucher.getStock() != null && voucher.getStock() > 0) {
                highlights.add("库存 " + voucher.getStock());
            }
            if (voucher.getRules() != null && !voucher.getRules().isBlank()) {
                highlights.add(voucher.getRules());
            }
            if (highlights.size() >= 3) {
                break;
            }
        }
        return highlights;
    }

    private String buildAnswer(String message, List<AgentRecommendation> recommendations) {
        if (recommendations.isEmpty()) {
            if (wantsVoucher(message)) {
                return "我暂时没找到同时匹配需求且有优惠券的店，可以放宽品类或预算，我再帮你筛一轮。";
            }
            return "我暂时没有找到特别匹配的店，可以换个关键词，比如火锅、KTV、美食，或者告诉我预算和想去的区域。";
        }
        Long budget = extractBudget(message);
        String budgetText = budget == null ? "" : "，并按人均 " + budget + " 元左右做了预算筛选";
        return "我按你的需求筛了 " + recommendations.size() + " 个候选店" + budgetText + "。你可以先看推荐理由和优惠券，确认后再订阅提醒或进入店铺详情。";
    }
}
