import os
import json
import logging
import re
from typing import Any

import httpx
from fastapi import FastAPI, Header
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field, field_validator

#运行uvicorn app.main:app --host 0.0.0.0 --port 8000

BACKEND_BASE_URL = os.getenv("SMART_LIFE_BACKEND_URL", "http://localhost:8081")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
OPENAI_STREAM_MODEL = os.getenv("OPENAI_STREAM_MODEL", OPENAI_MODEL)
LOG_RAW_LLM_RESPONSE = os.getenv("SMART_LIFE_AGENT_LOG_RAW_RESPONSE", "false").lower() == "true"
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("smart_life_agent")

app = FastAPI(title="Smart Life Guide Agent")


def is_blank(value: Any) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def parse_optional_int(value: Any) -> int | None:
    if is_blank(value):
        return None
    return int(value)


def parse_optional_float(value: Any) -> float | None:
    if is_blank(value):
        return None
    return float(value)


class ChatRequest(BaseModel):
    message: str = ""
    context: dict[str, Any] | None = None


class VoucherView(BaseModel):
    voucherId: int | str | None = None
    title: str | None = None
    subTitle: str | None = None
    rules: str | None = None
    payValue: int | str | None = None
    actualValue: int | str | None = None
    type: int | None = None
    status: int | None = None
    stock: int | None = None
    subscribeStatus: int | None = None
    beginTime: str | None = None
    endTime: str | None = None

    @field_validator("type", "status", "stock", "subscribeStatus", mode="before")
    @classmethod
    def empty_int_to_none(cls, value: Any) -> int | None:
        return parse_optional_int(value)


class Recommendation(BaseModel):
    shopId: int | str | None = None
    shopName: str | None = None
    area: str | None = None
    address: str | None = None
    avgPrice: int | str | None = None
    sold: int | None = None
    comments: int | None = None
    score: int | None = None
    openHours: str | None = None
    images: str | None = None
    distance: float | None = None
    reputationSummary: str | None = None
    scenarioTags: list[str] = Field(default_factory=list)
    couponHighlights: list[str] = Field(default_factory=list)
    reason: str | None = None
    vouchers: list[VoucherView] = Field(default_factory=list)

    @field_validator("sold", "comments", "score", mode="before")
    @classmethod
    def empty_int_to_none(cls, value: Any) -> int | None:
        return parse_optional_int(value)

    @field_validator("avgPrice", mode="before")
    @classmethod
    def empty_price_to_none(cls, value: Any) -> int | str | None:
        return None if is_blank(value) else value

    @field_validator("distance", mode="before")
    @classmethod
    def empty_float_to_none(cls, value: Any) -> float | None:
        return parse_optional_float(value)


class ChatResponse(BaseModel):
    answer: str
    suggestions: list[str] = Field(default_factory=list)
    recommendations: list[Recommendation] = Field(default_factory=list)
    toolTrace: list[str] = Field(default_factory=list)


class GuideContext(BaseModel):
    recommendations: list[Recommendation]
    maxAvgPrice: int | None = None
    requireVoucher: bool = False


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    authorization: str | None = Header(default=None),
) -> ChatResponse:
    headers = {}
    if authorization:
        headers["Authorization"] = authorization

    subscription_response = await build_subscription_response(request.message, request.context, headers)
    if subscription_response is not None:
        return subscription_response

    guide_context = await prepare_guide_context(request.message, headers)
    recommendations = guide_context.recommendations
    max_avg_price = guide_context.maxAvgPrice
    require_voucher = guide_context.requireVoucher

    agent_response = await build_agent_response(
        request.message,
        recommendations,
        max_avg_price,
        require_voucher,
    )
    if agent_response is not None:
        return agent_response

    if not recommendations and require_voucher:
        answer = "我暂时没找到同时匹配需求且有优惠券的店，可以放宽品类或预算，我再帮你筛一轮。"
    elif not recommendations:
        answer = "我暂时没有找到很匹配的店，可以换个关键词，或者告诉我预算、位置和想吃的品类。"
    else:
        budget_text = f"，并按人均 {max_avg_price} 元左右做了预算筛选" if max_avg_price else ""
        answer = f"我按你的需求筛了 {len(recommendations)} 个候选店{budget_text}。你可以先看理由和优惠券，再决定是否订阅提醒。"

    return ChatResponse(
        answer=answer,
        recommendations=recommendations,
        suggestions=[
            "帮我只看有优惠券的店",
            "人均 100 以内的美食店",
            "推荐适合今晚去的店",
            "这张券售罄时帮我订阅提醒",
        ],
        toolTrace=build_tool_trace(max_avg_price, require_voucher),
    )


@app.post("/chat/stream")
async def chat_stream(
    request: ChatRequest,
    authorization: str | None = Header(default=None),
) -> StreamingResponse:
    async def event_stream():
        headers = {}
        if authorization:
            headers["Authorization"] = authorization
        try:
            subscription_response = await build_subscription_response(request.message, request.context, headers)
            if subscription_response is not None:
                yield sse("trace", {"tool": subscription_response.toolTrace[0] if subscription_response.toolTrace else "subscribe_voucher"})
                yield sse("final", subscription_response.model_dump())
                return

            yield sse("trace", {"tool": "search_shops"})
            guide_context = await prepare_guide_context(request.message, headers)
            if guide_context.recommendations:
                yield sse("trace", {"tool": "query_shop_vouchers"})
            if guide_context.maxAvgPrice:
                yield sse("trace", {"tool": f"filter_budget:{guide_context.maxAvgPrice}"})
            if guide_context.requireVoucher:
                yield sse("trace", {"tool": "filter_has_voucher"})

            async for event in stream_agent_answer_events(request.message, guide_context):
                yield event
        except Exception as ex:
            logger.exception("Streaming agent chat failed")
            yield sse("error", {"message": str(ex)})

    return StreamingResponse(event_stream(), media_type="text/event-stream")


async def prepare_guide_context(message: str, headers: dict[str, str]) -> GuideContext:
    keyword = extract_keyword(message)
    type_id = infer_type_id(message)
    max_avg_price = extract_budget(message)
    require_voucher = wants_voucher(message) or wants_subscribe(message)
    recommendations = await search_shops(keyword, type_id, max_avg_price, require_voucher, headers)
    for recommendation in recommendations:
        recommendation.vouchers = await query_vouchers(recommendation.shopId, headers)
        recommendation.couponHighlights = build_coupon_highlights(recommendation.vouchers)
        if not recommendation.scenarioTags:
            recommendation.scenarioTags = build_scenario_tags(recommendation)
        if not recommendation.reputationSummary:
            recommendation.reputationSummary = build_reputation_summary(recommendation)
        recommendation.reason = build_reason(message, recommendation)
    return GuideContext(
        recommendations=recommendations,
        maxAvgPrice=max_avg_price,
        requireVoucher=require_voucher,
    )


async def search_shops(
    keyword: str | None,
    type_id: int | None,
    max_avg_price: int | None,
    require_voucher: bool,
    headers: dict[str, str],
) -> list[Recommendation]:
    payload = {
        "keyword": keyword,
        "typeId": type_id,
        "current": 1,
        "maxAvgPrice": max_avg_price,
        "requireVoucher": require_voucher,
    }
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.post(
            f"{BACKEND_BASE_URL}/agent/tools/search-shops",
            json=payload,
            headers=headers,
        )
        response.raise_for_status()
    data = response.json().get("data") or []
    return [Recommendation(**item) for item in data]


async def query_vouchers(
    shop_id: int | str | None,
    headers: dict[str, str],
) -> list[VoucherView]:
    if shop_id is None:
        return []
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.get(
            f"{BACKEND_BASE_URL}/agent/tools/shop/{shop_id}/vouchers",
            headers=headers,
        )
        response.raise_for_status()
    data = response.json().get("data") or []
    return [VoucherView(**item) for item in data]


async def build_subscription_response(
    message: str,
    context: dict[str, Any] | None,
    headers: dict[str, str],
) -> ChatResponse | None:
    if not wants_subscribe(message) and not wants_subscription_status(message):
        return None

    context_recommendations = build_context_recommendations(context)
    target_voucher = first_context_voucher(context_recommendations)
    target_shop = first_context_shop(context_recommendations)

    if target_voucher is None:
        guide_context = await prepare_guide_context(message, headers)
        context_recommendations = guide_context.recommendations
        target_voucher = first_context_voucher(context_recommendations)
        target_shop = first_context_shop(context_recommendations)

    if target_voucher is None or target_voucher.voucherId is None:
        return ChatResponse(
            answer="我还没有拿到可订阅的券，先帮你查一家有券的店，再点推荐卡里的提醒按钮会更稳。",
            recommendations=context_recommendations,
            suggestions=normalize_suggestions(["帮我只看有券的店", "换一家有券店", "人均100以内"]),
            toolTrace=["query_subscribe_status" if wants_subscription_status(message) else "subscribe_voucher"],
        )

    if wants_subscription_status(message):
        status = target_voucher.subscribeStatus
        if status is None:
            status = await get_subscribe_status(target_voucher.voucherId, headers)
        shop_name = target_shop.shopName if target_shop and target_shop.shopName else "这家店"
        voucher_title = target_voucher.title or target_voucher.subTitle or "这张券"
        return ChatResponse(
            answer=f"{shop_name} 的「{voucher_title}」当前状态是：{describe_subscribe_status(status)}。",
            recommendations=context_recommendations,
            suggestions=normalize_suggestions(["帮我订阅提醒", "换一家有券店", "查看店铺详情"]),
            toolTrace=["query_subscribe_status"],
        )

    status = await subscribe_voucher(target_voucher.voucherId, headers)
    shop_name = target_shop.shopName if target_shop and target_shop.shopName else "这家店"
    voucher_title = target_voucher.title or target_voucher.subTitle or "这张券"
    answer = f"已帮你订阅 {shop_name} 的「{voucher_title}」提醒。后续有可用机会会按现有券订阅队列处理。"
    if status == 2:
        answer = f"{shop_name} 的「{voucher_title}」你已经拿到券了，我帮你确认了当前状态。"
    elif status == 1:
        answer = f"已帮你订阅 {shop_name} 的「{voucher_title}」提醒，后续有可用机会会按队列处理。"

    return ChatResponse(
        answer=answer,
        recommendations=context_recommendations,
        suggestions=normalize_suggestions(["查看店铺详情", "换一家有券店", "继续按预算筛"]),
        toolTrace=["subscribe_voucher"],
    )


def build_context_recommendations(context: dict[str, Any] | None) -> list[Recommendation]:
    if not context:
        return []
    raw_recommendations = context.get("recommendations")
    if not isinstance(raw_recommendations, list):
        return []
    recommendations = []
    for item in raw_recommendations:
        if isinstance(item, dict):
            try:
                recommendations.append(Recommendation(**item))
            except Exception as ex:
                logger.warning("Ignored invalid recommendation context: %s", ex)
    return recommendations


def first_context_shop(recommendations: list[Recommendation]) -> Recommendation | None:
    return recommendations[0] if recommendations else None


def first_context_voucher(recommendations: list[Recommendation]) -> VoucherView | None:
    for recommendation in recommendations:
        if recommendation.vouchers:
            return recommendation.vouchers[0]
    return None


async def subscribe_voucher(
    voucher_id: int | str,
    headers: dict[str, str],
) -> int | None:
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.post(
            f"{BACKEND_BASE_URL}/agent/tools/voucher/{voucher_id}/subscribe",
            headers=headers,
        )
        response.raise_for_status()
    return response.json().get("data")


async def get_subscribe_status(
    voucher_id: int | str,
    headers: dict[str, str],
) -> int | None:
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.get(
            f"{BACKEND_BASE_URL}/agent/tools/voucher/{voucher_id}/subscribe/status",
            headers=headers,
        )
        response.raise_for_status()
    return response.json().get("data")


def describe_subscribe_status(status: int | None) -> str:
    if status == 2:
        return "已领取成功，可以去券包里查看"
    if status == 1:
        return "已订阅提醒，正在订阅队列中"
    return "未订阅或已取消"


def extract_keyword(message: str) -> str | None:
    lower = message.lower()
    if "火锅" in lower:
        return "火锅"
    if "ktv" in lower:
        return "KTV"
    if "酒吧" in lower:
        return "酒吧"
    if "spa" in lower or "按摩" in lower:
        return "SPA"
    return None


def infer_type_id(message: str) -> int | None:
    lower = message.lower()
    if any(word in lower for word in ["美食", "吃", "饭", "火锅"]):
        return 1
    if "ktv" in lower:
        return 6
    if "酒吧" in lower:
        return 7
    return None


def extract_budget(message: str) -> int | None:
    budget = None
    for match in re.finditer(r"(?:人均|预算|每人|单人)?\s*(\d{2,4})\s*(?:元|块|左右|以内|以下)?", message):
        candidate = int(match.group(1))
        if 20 <= candidate <= 1000:
            budget = candidate
    return budget


def wants_voucher(message: str) -> bool:
    return any(word in message for word in ["优惠", "券", "便宜", "折扣", "划算"])


def wants_subscribe(message: str) -> bool:
    return any(word in message for word in ["订阅", "提醒", "叫我", "通知", "subscribe", "remind"])


def wants_subscription_status(message: str) -> bool:
    return any(word in message for word in ["状态", "进度", "结果", "有没有订阅", "是否订阅", "查订阅", "查询订阅"])


def build_tool_trace(max_avg_price: int | None, require_voucher: bool) -> list[str]:
    trace = ["search_shops", "query_shop_vouchers", "analyze_reputation"]
    if max_avg_price:
        trace.append(f"filter_budget:{max_avg_price}")
    if require_voucher:
        trace.append("filter_has_voucher")
    return trace


def build_reason(message: str, recommendation: Recommendation) -> str:
    parts = []
    if recommendation.score is not None:
        parts.append(f"评分 {recommendation.score / 10:.1f}")
    if recommendation.comments:
        parts.append(f"{recommendation.comments} 条评论")
    if recommendation.sold:
        parts.append(f"近期销量 {recommendation.sold}")
    if recommendation.avgPrice is not None:
        parts.append(f"人均约 {recommendation.avgPrice} 元")
    budget = extract_budget(message)
    if budget is not None and recommendation.avgPrice is not None and int(recommendation.avgPrice) <= budget:
        parts.append("符合你的预算")
    if recommendation.vouchers:
        parts.append("当前有可用优惠券")
    if "附近" in message and recommendation.distance is not None:
        parts.append("距离较近")
    return "，".join(parts) + "。" if parts else "匹配你的本地生活需求，适合作为备选方案。"


def build_reputation_summary(recommendation: Recommendation) -> str:
    parts = []
    if recommendation.score is not None:
        parts.append(f"评分 {recommendation.score / 10:.1f}")
    if recommendation.comments:
        parts.append(f"{recommendation.comments} 条评论")
    if recommendation.sold:
        parts.append(f"销量 {recommendation.sold}")
    return "，".join(parts) + "。" if parts else "暂无足够口碑数据。"


def build_scenario_tags(recommendation: Recommendation) -> list[str]:
    tags = []
    if recommendation.score is not None and recommendation.score >= 40:
        tags.append("高评分")
    if recommendation.sold and recommendation.sold >= 1000:
        tags.append("人气店")
    if recommendation.comments and recommendation.comments >= 1000:
        tags.append("评论多")
    if recommendation.avgPrice is not None and int(recommendation.avgPrice) <= 100:
        tags.append("预算友好")
    if recommendation.openHours and "22" in recommendation.openHours:
        tags.append("适合晚餐")
    return tags


def build_coupon_highlights(vouchers: list[VoucherView]) -> list[str]:
    highlights = []
    for voucher in vouchers:
        if voucher.payValue is not None and voucher.actualValue is not None:
            saved = int(voucher.actualValue) - int(voucher.payValue)
            if saved > 0:
                highlights.append(f"立省 {saved} 元")
        if voucher.stock:
            highlights.append(f"库存 {voucher.stock}")
        if voucher.rules:
            highlights.append(voucher.rules)
        if len(highlights) >= 3:
            break
    return highlights


def sse(event: str, data: Any) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


async def stream_agent_answer_events(message: str, guide_context: GuideContext):
    recommendations = guide_context.recommendations
    trace = build_tool_trace(guide_context.maxAvgPrice, guide_context.requireVoucher)
    if not recommendations:
        answer = (
            "我暂时没找到同时匹配需求且有优惠券的店，可以放宽品类或预算，我再帮你筛一轮。"
            if guide_context.requireVoucher
            else "我暂时没有找到很匹配的店，可以换个关键词，或者告诉我预算、位置和想吃的品类。"
        )
        yield sse("final", ChatResponse(
            answer=answer,
            recommendations=recommendations,
            suggestions=normalize_suggestions(None),
            toolTrace=trace,
        ).model_dump())
        return

    if not OPENAI_API_KEY:
        answer = build_fallback_answer(message, recommendations, guide_context.maxAvgPrice)
        yield sse("final", ChatResponse(
            answer=answer,
            recommendations=recommendations,
            suggestions=normalize_suggestions(None),
            toolTrace=trace,
        ).model_dump())
        return

    try:
        from openai import AsyncOpenAI
    except ImportError:
        answer = build_fallback_answer(message, recommendations, guide_context.maxAvgPrice)
        yield sse("final", ChatResponse(
            answer=answer,
            recommendations=recommendations,
            suggestions=normalize_suggestions(None),
            toolTrace=trace,
        ).model_dump())
        return

    client_kwargs = {"api_key": OPENAI_API_KEY}
    if OPENAI_BASE_URL:
        client_kwargs["base_url"] = OPENAI_BASE_URL
    client = AsyncOpenAI(**client_kwargs)

    facts = [to_agent_fact(recommendation) for recommendation in recommendations]
    prompt = (
        "你是 Smart Life 本地生活导购 Agent。"
        "请只基于用户需求和店铺/优惠券事实，生成一段给用户看的中文导购回答。"
        "不要输出 JSON，不要使用 Markdown，不要编造事实。"
        "请自然说明为什么这家店和券值得看，控制在 120 字以内。"
    )
    user_payload = {
        "user_message": message,
        "filters": {
            "max_avg_price": guide_context.maxAvgPrice,
            "require_voucher": guide_context.requireVoucher,
        },
        "recommendations": facts,
    }

    chunks: list[str] = []
    try:
        logger.info("Streaming LLM answer: model=%s recommendations=%d", OPENAI_STREAM_MODEL, len(recommendations))
        stream = await client.chat.completions.create(
            model=OPENAI_STREAM_MODEL,
            temperature=0.2,
            max_tokens=220,
            stream=True,
            messages=[
                {"role": "system", "content": prompt},
                {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
            ],
        )
        async for chunk in stream:
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta.content or ""
            if delta:
                chunks.append(delta)
                yield sse("answer_delta", {"content": delta})
    except Exception as ex:
        logger.warning("Streaming LLM answer failed: %s", ex)

    answer = "".join(chunks).strip()
    if answer:
        if len(recommendations) == 1:
            recommendations[0].reason = answer
        yield sse("final", ChatResponse(
            answer=answer,
            recommendations=recommendations,
            suggestions=normalize_suggestions(None),
            toolTrace=trace + ["llm_coupon_explainer"],
        ).model_dump())
        return

    response = await build_agent_response(
        message,
        recommendations,
        guide_context.maxAvgPrice,
        guide_context.requireVoucher,
    )
    if response is None:
        response = ChatResponse(
            answer=build_fallback_answer(message, recommendations, guide_context.maxAvgPrice),
            recommendations=recommendations,
            suggestions=normalize_suggestions(None),
            toolTrace=trace,
        )
    yield sse("final", response.model_dump())


def build_fallback_answer(message: str, recommendations: list[Recommendation], max_avg_price: int | None) -> str:
    if not recommendations:
        return "我暂时没有找到很匹配的店，可以换个关键词，或者告诉我预算、位置和想吃的品类。"
    budget_text = f"，并按人均 {max_avg_price} 元左右做了预算筛选" if max_avg_price else ""
    return f"我按你的需求筛了 {len(recommendations)} 个候选店{budget_text}。你可以先看理由和优惠券，再决定是否订阅提醒。"


async def build_agent_response(
    message: str,
    recommendations: list[Recommendation],
    max_avg_price: int | None,
    require_voucher: bool,
) -> ChatResponse | None:
    if not OPENAI_API_KEY or not recommendations:
        if not OPENAI_API_KEY:
            logger.info("LLM coupon explainer skipped: OPENAI_API_KEY is not configured")
        if not recommendations:
            logger.info("LLM coupon explainer skipped: no recommendations")
        return None

    try:
        from openai import AsyncOpenAI
    except ImportError:
        logger.warning("LLM coupon explainer skipped: openai package is not installed")
        return None

    client_kwargs = {"api_key": OPENAI_API_KEY}
    if OPENAI_BASE_URL:
        client_kwargs["base_url"] = OPENAI_BASE_URL
    client = AsyncOpenAI(**client_kwargs)

    facts = [to_agent_fact(recommendation) for recommendation in recommendations]
    user_payload = {
        "user_message": message,
        "filters": {
            "max_avg_price": max_avg_price,
            "require_voucher": require_voucher,
        },
        "recommendations": facts,
    }
    system_prompt = (
        "你是 Smart Life 本地生活导购 Agent。"
        "只能基于传入的店铺、评分、销量、评论数、营业时间、地址和优惠券事实回答，不能编造不存在的数据。"
        "可参考 reputationSummary、scenarioTags、couponHighlights 做口碑和场景判断。"
        "重点解释优惠券是否划算、适合什么消费场景、是否符合用户预算。"
        "suggestions 是前端底部快捷按钮，必须是 3 到 4 个很短的下一步用户指令，每条不超过 14 个汉字。"
        "不要把补充分析、注意事项或长句放进 suggestions。"
        "示例 suggestions: [\"看看附近路线\", \"只看更高评分\", \"帮我订阅提醒\"]。"
        "返回 JSON，格式为 {\"answer\":\"...\",\"reasons\":[{\"shopId\":\"...\",\"reason\":\"...\"}],\"suggestions\":[\"...\"]}。"
    )

    try:
        logger.info("Calling LLM coupon explainer: model=%s recommendations=%d", OPENAI_MODEL, len(recommendations))
        completion = await client.chat.completions.create(
            model=OPENAI_MODEL,
            temperature=0.2,
            max_tokens=500,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
            ],
        )
    except Exception as ex:
        logger.warning("LLM coupon explainer failed: %s", ex)
        return None

    content = completion.choices[0].message.content or ""
    if LOG_RAW_LLM_RESPONSE:
        logger.info("LLM coupon explainer raw response: %s", content)
    try:
        parsed = json.loads(content)
    except json.JSONDecodeError:
        logger.warning("LLM coupon explainer returned non-JSON content")
        return None
    logger.info("LLM coupon explainer succeeded")

    reasons = parsed.get("reasons") or []
    reason_by_shop_id = {
        str(item.get("shopId")): item.get("reason")
        for item in reasons
        if item.get("shopId") is not None and item.get("reason")
    }
    for recommendation in recommendations:
        reason = reason_by_shop_id.get(str(recommendation.shopId))
        if reason:
            recommendation.reason = reason

    suggestions = normalize_suggestions(parsed.get("suggestions"))

    return ChatResponse(
        answer=parsed.get("answer") or "我按真实店铺和优惠券数据做了推荐，你可以先看推荐理由再决定。",
        recommendations=recommendations,
        suggestions=suggestions,
        toolTrace=build_tool_trace(max_avg_price, require_voucher) + ["llm_coupon_explainer"],
    )


def normalize_suggestions(suggestions: Any) -> list[str]:
    defaults = [
        "只看更高评分",
        "人均100以内",
        "帮我订阅提醒",
        "换一家有券店",
    ]
    if not isinstance(suggestions, list):
        return defaults

    normalized = []
    for item in suggestions:
        text = str(item).strip()
        if text and len(text) <= 18:
            normalized.append(text)
    return normalized[:4] if normalized else defaults


def to_agent_fact(recommendation: Recommendation) -> dict[str, Any]:
    return {
        "shopId": recommendation.shopId,
        "shopName": recommendation.shopName,
        "area": recommendation.area,
        "address": recommendation.address,
        "avgPrice": recommendation.avgPrice,
        "score": recommendation.score / 10 if recommendation.score is not None else None,
        "sold": recommendation.sold,
        "comments": recommendation.comments,
        "openHours": recommendation.openHours,
        "distance": recommendation.distance,
        "reputationSummary": recommendation.reputationSummary,
        "scenarioTags": recommendation.scenarioTags,
        "couponHighlights": recommendation.couponHighlights,
        "vouchers": [
            {
                "voucherId": voucher.voucherId,
                "title": voucher.title,
                "subTitle": voucher.subTitle,
                "rules": voucher.rules,
                "payValue": voucher.payValue,
                "actualValue": voucher.actualValue,
                "type": voucher.type,
                "stock": voucher.stock,
                "beginTime": voucher.beginTime,
                "endTime": voucher.endTime,
            }
            for voucher in recommendation.vouchers
        ],
    }
