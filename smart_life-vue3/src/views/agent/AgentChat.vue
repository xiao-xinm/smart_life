<script setup>
import { agentChatStream, subscribeVoucherByAgent } from '@/api/agent'
import {
  getSubscribeStatusBatch,
  getUnreadNotifications,
  markNotificationsRead
} from '@/api/voucher'
import router from '@/router'
import { useUserStore } from '@/stores'
import { Location, Promotion, Search, Shop, Star, Tickets } from '@element-plus/icons-vue'
import { nextTick, onMounted, ref } from 'vue'

const userStore = useUserStore()
const input = ref('今晚想找附近适合吃饭的店，最好有优惠券')
const loading = ref(false)
const hasSearched = ref(false)
const toolTrace = ref([])
const activeStep = ref('')
const messages = ref([
  {
    role: 'agent',
    content: '你好，我是 Smart Life AI 导购。告诉我想吃什么、预算、位置或是否想要优惠券，我会帮你筛店和券。'
  }
])
const recommendations = ref([])
const suggestions = ref([
  '今晚想吃火锅，人均 100 左右',
  '帮我找有优惠券的美食店',
  '推荐适合周末和朋友去的店'
])
const subscribeStatusMap = ref({})
const chatBodyRef = ref(null)

const processSteps = [
  { key: 'search_shops', label: '找店' },
  { key: 'query_shop_vouchers', label: '查券' },
  { key: 'llm_coupon_explainer', label: '分析' },
  { key: 'query_subscribe_status', label: '状态' },
  { key: 'subscribe_voucher', label: '订阅' }
]

onMounted(() => {
  loadUnreadNotifications()
})

const loadUnreadNotifications = async () => {
  if (!userStore.token) {
    return
  }
  try {
    const res = await getUnreadNotifications()
    const list = Array.isArray(res?.data) ? res.data : []
    if (!list.length) {
      return
    }
    list.slice(0, 2).forEach((item) => {
      messages.value.push({
        role: 'agent',
        content: item.content || `${item.shopName || '你订阅的店铺'} 可以抢了，可以去我的券提醒查看。`
      })
    })
    await markNotificationsRead()
    await scrollToBottom()
  } catch (error) {
    // 鍙戝埜閫氱煡鍔犺浇澶辫触涓嶅奖鍝嶅璐富娴佺▼銆
  }
}

const sendMessage = async (text = input.value) => {
  const message = (text || '').trim()
  if (!message || loading.value) {
    return
  }
  messages.value.push({ role: 'user', content: message })
  input.value = ''
  loading.value = true
  activeStep.value = 'search_shops'
  const requestContext = buildAgentContext()
  await scrollToBottom()
  const agentMessage = { role: 'agent', content: '' }
  messages.value.push(agentMessage)
  recommendations.value = []
  toolTrace.value = []
  try {
    await agentChatStream(message, requestContext, {
      trace(data) {
        if (data.tool && !toolTrace.value.includes(data.tool)) {
          toolTrace.value.push(data.tool)
        }
        updateActiveStep(data.tool)
      },
      answer_delta(data) {
        agentMessage.content += data.content || ''
        scrollToBottom()
      },
      final(data) {
        if (!agentMessage.content.trim()) {
          agentMessage.content = data.answer || '我已经帮你看了一下，可以从下面几个候选店开始。'
        }
        recommendations.value = data.recommendations || []
        toolTrace.value = data.toolTrace || toolTrace.value
        if (toolTrace.value.includes('subscribe_voucher')) {
          markFirstVoucherSubscribed(recommendations.value)
        }
        syncSubscribeStatus(recommendations.value)
        activeStep.value = data.toolTrace?.includes('llm_coupon_explainer') ? 'llm_coupon_explainer' : ''
        hasSearched.value = true
        suggestions.value = data.suggestions?.length ? data.suggestions : suggestions.value
      },
      error(data) {
        throw new Error(data.message || 'Agent stream failed')
      }
    })
  } catch (error) {
    agentMessage.content = agentMessage.content || '导购服务暂时没接上，我稍后再帮你筛。'
    hasSearched.value = true
    recommendations.value = []
  } finally {
    loading.value = false
    activeStep.value = ''
    await scrollToBottom()
  }
}

const askOnlyVoucher = () => {
  sendMessage('帮我只看有优惠券的店')
}

const scrollToBottom = async () => {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  }
}

const openShop = (shopId) => {
  router.push(`/shopDetail/${shopId}`)
}

const subscribeVoucher = async (voucherId) => {
  try {
    const res = await subscribeVoucherByAgent(voucherId)
    setSubscribeStatus(voucherId, Number(res?.data ?? 1))
    ElMessage.success(getSubscribeStatusLabel(voucherId))
  } catch (error) {
    ElMessage.error('订阅失败，请确认已登录')
  }
}

const buildAgentContext = () => ({
  recommendations: recommendations.value.map((item) => ({
    ...item,
    vouchers: (item.vouchers || []).map((voucher) => ({
      ...voucher,
      subscribeStatus: getSubscribeStatus(voucher.voucherId)
    }))
  }))
})

const markFirstVoucherSubscribed = (items = []) => {
  const voucher = items.flatMap((item) => item.vouchers || [])[0]
  if (!voucher?.voucherId) {
    return
  }
  setSubscribeStatus(voucher.voucherId, 1)
}

const syncSubscribeStatus = async (items = []) => {
  if (!userStore.token) {
    return
  }
  const voucherIds = items
    .flatMap((item) => item.vouchers || [])
    .map((voucher) => voucher.voucherId)
    .filter(Boolean)
  if (!voucherIds.length) {
    return
  }
  try {
    const res = await getSubscribeStatusBatch(voucherIds)
    const statuses = Array.isArray(res?.data) ? res.data : []
    const next = { ...subscribeStatusMap.value }
    statuses.forEach((item) => {
      next[String(item.voucherId)] = Number(item.subscribeStatus || 0)
    })
    subscribeStatusMap.value = next
  } catch (error) {
    // 状态同步失败不影响导购结果展示。
  }
}

const setSubscribeStatus = (voucherId, status) => {
  subscribeStatusMap.value = {
    ...subscribeStatusMap.value,
    [String(voucherId)]: status
  }
}

const updateActiveStep = (tool) => {
  if (tool === 'query_shop_vouchers' || tool?.startsWith('filter_')) {
    activeStep.value = 'query_shop_vouchers'
    return
  }
  if (tool === 'llm_coupon_explainer') {
    activeStep.value = 'llm_coupon_explainer'
    return
  }
  if (tool === 'subscribe_voucher') {
    activeStep.value = 'subscribe_voucher'
    return
  }
  if (tool === 'query_subscribe_status') {
    activeStep.value = 'query_subscribe_status'
    return
  }
  activeStep.value = 'search_shops'
}

const formatScore = (score) => {
  if (!score) {
    return '暂无'
  }
  return (score / 10).toFixed(1)
}

const formatVoucherValue = (voucher) => {
  if (voucher.payValue && voucher.actualValue) {
    return `¥${voucher.payValue} 抵 ¥${voucher.actualValue}`
  }
  return voucher.title || '优惠券'
}

const getSubscribeStatus = (voucherId) => Number(subscribeStatusMap.value[String(voucherId)] || 0)

const isSubscribed = (voucherId) => getSubscribeStatus(voucherId) > 0

const getSubscribeStatusLabel = (voucherId) => {
  const status = getSubscribeStatus(voucherId)
  if (status === 2) {
    return '已领取'
  }
  if (status === 1) {
    return '已提醒'
  }
  return '提醒'
}

const firstImage = (images) => {
  if (!images) {
    return ''
  }
  return String(images).split(',')[0]?.trim()
}
</script>

<template>
  <div class="agent-page">
    <header class="agent-header">
      <button class="back-btn" @click="router.back()">‹</button>
      <div class="header-copy">
        <div class="agent-title">AI 导购</div>
        <div class="agent-subtitle">找店 · 看券 · 订阅提醒</div>
      </div>
      <button class="filter-btn" type="button" @click="askOnlyVoucher">有券优先</button>
    </header>

    <section ref="chatBodyRef" class="chat-body">
      <div class="quick-panel">
        <div class="quick-title">今天想怎么安排？</div>
        <div class="quick-tags">
          <button type="button" @click="sendMessage('人均 100 以内，有优惠券的美食店')">预算内有券</button>
          <button type="button" @click="sendMessage('评分高一点，适合今晚吃饭')">高分晚餐</button>
          <button type="button" @click="sendMessage('找适合朋友聚会的店')">朋友聚会</button>
        </div>
      </div>

      <div
        v-for="(message, index) in messages"
        :key="index"
        class="message-row"
        :class="message.role"
      >
        <div class="message-bubble">
          <span v-if="message.content">{{ message.content }}</span>
          <span v-else class="typing-cursor">正在思考</span>
        </div>
      </div>

      <div v-if="loading || toolTrace.length" class="process-card">
        <div
          v-for="step in processSteps"
          :key="step.key"
          class="process-step"
          :class="{ active: activeStep === step.key, done: toolTrace.includes(step.key) }"
        >
          <span class="step-dot"></span>
          <span>{{ step.label }}</span>
        </div>
        <div v-if="toolTrace.length" class="tool-trace">
          <span v-for="tool in toolTrace" :key="tool">{{ tool }}</span>
        </div>
      </div>

      <div v-if="recommendations.length" class="recommendation-list">
        <article
          v-for="item in recommendations"
          :key="item.shopId"
          class="recommendation-card"
        >
          <div v-if="firstImage(item.images)" class="shop-cover">
            <img :src="firstImage(item.images)" :alt="item.shopName" />
          </div>
          <div class="shop-card-head">
            <div>
              <div class="shop-name">{{ item.shopName }}</div>
              <div class="shop-meta">
                <span><el-icon><Location /></el-icon>{{ item.area || '本地商圈' }}</span>
                <span v-if="item.avgPrice">人均 ¥{{ item.avgPrice }}</span>
                <span><el-icon><Star /></el-icon>{{ formatScore(item.score) }}</span>
              </div>
            </div>
            <el-button :icon="Shop" circle @click="openShop(item.shopId)" />
          </div>
          <div class="shop-stats">
            <div>
              <strong>{{ item.sold || '-' }}</strong>
              <span>销量</span>
            </div>
            <div>
              <strong>{{ item.comments || '-' }}</strong>
              <span>评论</span>
            </div>
            <div>
              <strong>{{ item.openHours || '暂无' }}</strong>
              <span>营业</span>
            </div>
          </div>
          <div v-if="item.scenarioTags?.length" class="scenario-tags">
            <span v-for="tag in item.scenarioTags" :key="tag">{{ tag }}</span>
          </div>
          <p class="reason">{{ item.reason }}</p>
          <p v-if="item.reputationSummary" class="reputation-summary">{{ item.reputationSummary }}</p>
          <div v-if="item.address" class="address-line">
            <el-icon><Location /></el-icon>
            <span>{{ item.address }}</span>
          </div>
          <div v-if="item.couponHighlights?.length" class="coupon-highlights">
            <span v-for="highlight in item.couponHighlights" :key="highlight">{{ highlight }}</span>
          </div>
          <div v-if="item.vouchers?.length" class="voucher-list">
            <div
              v-for="voucher in item.vouchers"
              :key="voucher.voucherId"
              class="voucher-row"
            >
              <div class="voucher-info">
                <el-icon><Tickets /></el-icon>
                <div>
                  <div class="voucher-title">{{ formatVoucherValue(voucher) }}</div>
                  <div class="voucher-sub">{{ voucher.subTitle || voucher.rules || '优惠券' }}</div>
                </div>
              </div>
              <el-button
                size="small"
                type="primary"
                plain
                :disabled="isSubscribed(voucher.voucherId)"
                @click="subscribeVoucher(voucher.voucherId)"
              >
                {{ getSubscribeStatusLabel(voucher.voucherId) }}
              </el-button>
            </div>
          </div>
          <div v-else class="no-voucher">暂无可用优惠券，可以先进入店铺详情看看。</div>
        </article>
      </div>
      <div v-else-if="hasSearched && !loading" class="empty-state">
        <div class="empty-title">这轮没有命中合适的店</div>
        <div class="empty-copy">试试放宽预算、去掉优惠券限制，或者换成“美食”“火锅”“KTV”这类关键词。</div>
      </div>
    </section>

    <section class="suggestions">
      <button
        v-for="suggestion in suggestions"
        :key="suggestion"
        type="button"
        @click="sendMessage(suggestion)"
      >
        {{ suggestion }}
      </button>
    </section>

    <footer class="composer">
      <el-input
        v-model="input"
        class="composer-input"
        placeholder="说说你想找什么店"
        @keyup.enter="sendMessage()"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button
        type="primary"
        :icon="Promotion"
        :loading="loading"
        circle
        @click="sendMessage()"
      />
    </footer>
  </div>
</template>

<style scoped>
.agent-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fb;
  color: #1f2933;
}

.agent-header {
  min-height: 68px;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  background: #ffffff;
  border-bottom: 1px solid #ebeef5;
  box-shadow: 0 2px 14px rgba(15, 23, 42, 0.05);
  z-index: 2;
}

.filter-btn {
  margin-left: auto;
  border: 1px solid #fed7aa;
  border-radius: 999px;
  padding: 8px 12px;
  background: #fff7ed;
  color: #c2410c;
  font-size: 12px;
  font-weight: 700;
}

.back-btn {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 12px;
  background: #eef2f7;
  font-size: 28px;
  line-height: 30px;
  color: #303133;
}

.header-copy {
  min-width: 0;
}

.agent-title {
  font-size: 18px;
  font-weight: 700;
}

.agent-subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: #7b8794;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 14px 8px;
}

.quick-panel {
  margin-bottom: 14px;
  padding: 14px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e5eaf3;
}

.quick-title {
  font-size: 13px;
  font-weight: 700;
  color: #334155;
}

.quick-tags {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  overflow-x: auto;
}

.quick-tags button {
  flex: 0 0 auto;
  border: 1px solid #dbeafe;
  border-radius: 999px;
  padding: 7px 10px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
}

.message-row {
  display: flex;
  margin-bottom: 12px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 78%;
  padding: 12px 14px;
  border-radius: 8px;
  line-height: 1.5;
  font-size: 14px;
  background: #ffffff;
  border: 1px solid #e9edf5;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.05);
}

.message-row.user .message-bubble {
  background: #1d4ed8;
  color: #ffffff;
  border-color: #1d4ed8;
}

.typing-cursor {
  color: #64748b;
}

.typing-cursor::after {
  content: "";
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: #3b82f6;
  animation: pulse-dot 1s infinite ease-in-out;
}

@keyframes pulse-dot {
  0%, 100% {
    opacity: 0.35;
    transform: translateY(0);
  }
  50% {
    opacity: 1;
    transform: translateY(-2px);
  }
}

.process-card {
  margin: 4px 0 12px;
  padding: 12px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e5eaf3;
}

.process-step {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: 12px;
  font-size: 12px;
  color: #94a3b8;
}

.process-step.done,
.process-step.active {
  color: #0f766e;
  font-weight: 700;
}

.step-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
}

.process-step.done .step-dot,
.process-step.active .step-dot {
  background: #14b8a6;
}

.recommendation-list {
  display: grid;
  gap: 12px;
  margin: 8px 0 16px;
}

.tool-trace {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tool-trace span {
  border-radius: 999px;
  padding: 4px 8px;
  background: #e0f2fe;
  color: #075985;
  font-size: 11px;
}

.recommendation-card {
  overflow: hidden;
  padding: 0 0 14px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e5eaf3;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.shop-cover {
  height: 124px;
  background: #e2e8f0;
}

.shop-cover img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.shop-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 14px 14px 0;
}

.shop-name {
  font-size: 16px;
  font-weight: 700;
}

.shop-meta {
  margin-top: 5px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: #7b8794;
}

.shop-meta span {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.shop-stats {
  margin: 12px 14px 0;
  display: grid;
  grid-template-columns: 1fr 1fr 1.35fr;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  overflow: hidden;
}

.shop-stats div {
  min-width: 0;
  padding: 9px 8px;
  display: grid;
  gap: 3px;
  background: #f8fafc;
  border-right: 1px solid #edf2f7;
}

.shop-stats div:last-child {
  border-right: 0;
}

.shop-stats strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #0f172a;
  font-size: 13px;
}

.shop-stats span {
  color: #64748b;
  font-size: 11px;
}

.scenario-tags,
.coupon-highlights {
  margin: 10px 14px 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.scenario-tags span {
  border-radius: 999px;
  padding: 4px 8px;
  background: #ecfeff;
  color: #0e7490;
  font-size: 11px;
  font-weight: 700;
}

.coupon-highlights span {
  border-radius: 999px;
  padding: 4px 8px;
  background: #fef3c7;
  color: #92400e;
  font-size: 11px;
  font-weight: 700;
}

.reason {
  margin: 12px 14px 0;
  line-height: 1.5;
  font-size: 13px;
  color: #334155;
}

.reputation-summary {
  margin: 8px 14px 0;
  line-height: 1.45;
  font-size: 12px;
  color: #64748b;
}

.address-line {
  margin: 10px 14px 0;
  display: flex;
  gap: 5px;
  align-items: flex-start;
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
}

.voucher-list {
  margin: 12px 14px 0;
  display: grid;
  gap: 8px;
}

.no-voucher {
  margin-top: 12px;
  border-radius: 8px;
  padding: 9px 10px;
  background: #f8fafc;
  color: #64748b;
  font-size: 12px;
}

.voucher-row {
  min-height: 58px;
  padding: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-radius: 8px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
}

.voucher-info {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #c2410c;
}

.voucher-title {
  font-size: 14px;
  font-weight: 700;
  color: #9a3412;
}

.voucher-sub {
  margin-top: 2px;
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #c2410c;
}

.suggestions {
  padding: 8px 12px 9px;
  display: flex;
  gap: 8px;
  overflow-x: auto;
  background: #f5f7fb;
}

.suggestions button {
  flex: 0 0 auto;
  border: 1px solid #d8dee9;
  border-radius: 999px;
  padding: 7px 11px;
  background: #ffffff;
  color: #4b5563;
  font-size: 12px;
}

.empty-state {
  margin: 18px 0;
  padding: 16px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px dashed #cbd5e1;
}

.empty-title {
  font-size: 15px;
  font-weight: 700;
  color: #334155;
}

.empty-copy {
  margin-top: 6px;
  line-height: 1.5;
  color: #64748b;
  font-size: 13px;
}

.composer {
  padding: 10px 12px 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: #ffffff;
  border-top: 1px solid #ebeef5;
  box-shadow: 0 -8px 20px rgba(15, 23, 42, 0.05);
}

.composer-input {
  flex: 1;
}
</style>
