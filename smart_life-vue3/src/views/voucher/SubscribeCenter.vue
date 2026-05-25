<script setup>
import { computed, onMounted, ref } from 'vue'
import router from '@/router'
import {
  getNotifications,
  getSubscribeCenter,
  markNotificationsRead
} from '@/api/voucher'
import {
  ArrowLeft,
  Bell,
  Check,
  Location,
  Shop,
  Tickets
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const activeTab = ref('all')
const subscriptions = ref([])
const notifications = ref([])

const tabs = [
  { key: 'all', label: '全部' },
  { key: 'subscribed', label: '已订阅' },
  { key: 'issued', label: '已领取' }
]

const filteredSubscriptions = computed(() => {
  if (activeTab.value === 'subscribed') {
    return subscriptions.value.filter((item) => Number(item.subscribeStatus) === 1)
  }
  if (activeTab.value === 'issued') {
    return subscriptions.value.filter((item) => Number(item.subscribeStatus) === 2)
  }
  return subscriptions.value
})

const unreadCount = computed(() =>
  notifications.value.filter((item) => !item.read).length
)

onMounted(() => {
  loadData()
})

const loadData = async () => {
  loading.value = true
  try {
    const [subscribeRes, notifyRes] = await Promise.all([
      getSubscribeCenter(),
      getNotifications()
    ])
    subscriptions.value = Array.isArray(subscribeRes?.data) ? subscribeRes.data : []
    notifications.value = Array.isArray(notifyRes?.data) ? notifyRes.data : []
  } catch (error) {
    ElMessage.error('加载券提醒失败')
  } finally {
    loading.value = false
  }
}

const markAllRead = async () => {
  if (!notifications.value.length) {
    return
  }
  try {
    await markNotificationsRead()
    notifications.value = notifications.value.map((item) => ({ ...item, read: true }))
    ElMessage.success('已全部标记为已读')
  } catch (error) {
    ElMessage.error('标记已读失败')
  }
}

const openShop = (shopId) => {
  if (shopId) {
    router.push(`/shopDetail/${shopId}`)
  }
}

const openVoucherOrder = (item) => {
  const query = item?.orderId ? `?orderId=${item.orderId}` : ''
  router.push(`/voucher/orders${query}`)
}

const statusText = (status) => {
  if (Number(status) === 2) {
    return '已领取'
  }
  return '已订阅'
}

const statusClass = (status) => (Number(status) === 2 ? 'issued' : 'subscribed')

const money = (value) => Number(value || 0)
</script>

<template>
  <div class="subscribe-page">
    <header class="page-header">
      <button class="icon-btn" type="button" @click="router.back()">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <div>
        <h1>我的券提醒</h1>
        <p>订阅状态、开抢提醒和已领取结果</p>
      </div>
    </header>

    <section class="notify-panel">
      <div class="section-head">
        <div>
          <span class="eyebrow">站内通知</span>
          <h2>提醒通知</h2>
        </div>
        <button type="button" :disabled="!unreadCount" @click="markAllRead">
          全部已读
        </button>
      </div>

      <div v-if="notifications.length" class="notify-list">
        <article
          v-for="item in notifications"
          :key="item.id"
          class="notify-item"
          :class="{ unread: !item.read }"
        >
          <div class="notify-icon">
            <el-icon><Bell /></el-icon>
          </div>
          <div>
            <div class="notify-title">
              {{ item.title || '开抢提醒' }}
              <span v-if="!item.read">未读</span>
            </div>
            <p>{{ item.content }}</p>
          </div>
        </article>
      </div>
      <div v-else class="empty-state">
        还没有提醒通知，订阅的券到开抢时间后会出现在这里。
      </div>
    </section>

    <section class="subscription-panel">
      <div class="tab-row">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>

      <div v-if="loading" class="empty-state">加载中...</div>

      <div v-else-if="filteredSubscriptions.length" class="subscription-list">
        <article
          v-for="item in filteredSubscriptions"
          :key="item.voucherId"
          class="subscription-card"
        >
          <div class="card-body">
            <div class="card-title-row">
              <div>
                <h3>{{ item.shopName || '未知店铺' }}</h3>
                <p>
                  <el-icon><Location /></el-icon>
                  {{ item.shopArea || item.shopAddress || '暂无地址' }}
                </p>
              </div>
              <span class="status-pill" :class="statusClass(item.subscribeStatus)">
                <el-icon v-if="Number(item.subscribeStatus) === 2"><Check /></el-icon>
                {{ statusText(item.subscribeStatus) }}
              </span>
            </div>

            <div class="voucher-box">
              <div>
                <div class="voucher-title">
                  <el-icon><Tickets /></el-icon>
                  {{ item.voucherTitle || '优惠券' }}
                </div>
                <p>{{ item.voucherSubTitle || item.voucherRules || '到券后可直接下单使用' }}</p>
              </div>
              <div class="voucher-price">
                <span>￥{{ money(item.payValue) }}</span>
                <small>抵 ￥{{ money(item.actualValue) }}</small>
              </div>
            </div>

            <button
              v-if="Number(item.subscribeStatus) === 2"
              class="shop-btn issued-btn"
              type="button"
              @click="openVoucherOrder(item)"
            >
              <el-icon><Tickets /></el-icon>
              查看券包
            </button>
            <button v-else class="shop-btn" type="button" @click="openShop(item.shopId)">
              <el-icon><Shop /></el-icon>
              进入店铺
            </button>
          </div>
        </article>
      </div>

      <div v-else class="empty-state">
        当前没有{{ activeTab === 'issued' ? '已领取' : activeTab === 'subscribed' ? '已订阅' : '' }}券提醒。
      </div>
    </section>
  </div>
</template>

<style scoped>
.subscribe-page {
  min-height: 100vh;
  background: #eef3f9;
  color: #111827;
  padding-bottom: 18px;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 16px 14px;
  background: linear-gradient(135deg, #f8fbff 0%, #e9f2ff 100%);
  border-bottom: 1px solid #dbe7f5;
}

.icon-btn {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 50%;
  background: #fff;
  color: #1f2937;
  display: grid;
  place-items: center;
  box-shadow: 0 6px 18px rgba(25, 73, 123, 0.12);
}

.page-header h1,
.section-head h2,
.subscription-card h3 {
  margin: 0;
}

.page-header h1 {
  font-size: 19px;
}

.page-header p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
}

.notify-panel,
.subscription-panel {
  margin: 12px;
  background: #fff;
  border: 1px solid #dfe8f3;
  border-radius: 8px;
  padding: 14px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
}

.section-head h2 {
  font-size: 18px;
  margin-top: 2px;
}

.section-head button,
.shop-btn,
.tab-row button {
  border: 0;
  cursor: pointer;
}

.section-head button {
  padding: 7px 12px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
}

.section-head button:disabled {
  color: #94a3b8;
  background: #f1f5f9;
}

.notify-list {
  display: grid;
  gap: 10px;
}

.notify-item {
  display: grid;
  grid-template-columns: 34px 1fr;
  gap: 10px;
  padding: 11px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
}

.notify-item.unread {
  background: #fff7ed;
  border-color: #fed7aa;
}

.notify-icon {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #e0f2fe;
  color: #0284c7;
}

.notify-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-weight: 800;
  font-size: 14px;
}

.notify-title span {
  color: #ea580c;
  font-size: 11px;
  font-weight: 700;
}

.notify-item p {
  margin: 5px 0 0;
  color: #475569;
  line-height: 1.5;
  font-size: 13px;
}

.tab-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}

.tab-row button {
  height: 36px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #475569;
  font-weight: 700;
}

.tab-row button.active {
  background: #2563eb;
  color: #fff;
}

.subscription-list {
  display: grid;
  gap: 12px;
}

.subscription-card {
  overflow: hidden;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.card-body {
  padding: 13px;
}

.card-title-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.card-title-row h3 {
  font-size: 17px;
}

.card-title-row p {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #64748b;
  font-size: 12px;
  margin: 5px 0 0;
}

.status-pill {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.status-pill.subscribed {
  background: #eff6ff;
  color: #2563eb;
}

.status-pill.issued {
  background: #dcfce7;
  color: #15803d;
}

.voucher-box {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding: 12px;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  background: #fff7ed;
}

.voucher-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #9a3412;
  font-weight: 800;
  font-size: 14px;
}

.voucher-box p {
  margin: 5px 0 0;
  color: #ea580c;
  font-size: 12px;
}

.voucher-price {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
}

.voucher-price span {
  color: #dc2626;
  font-size: 18px;
  font-weight: 900;
}

.voucher-price small {
  color: #9a3412;
  font-size: 12px;
}

.shop-btn {
  width: 100%;
  height: 38px;
  margin-top: 12px;
  border-radius: 8px;
  background: #111827;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-weight: 800;
}

.issued-btn {
  background: #15803d;
}

.empty-state {
  padding: 24px 12px;
  text-align: center;
  color: #64748b;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  font-size: 13px;
}
</style>
