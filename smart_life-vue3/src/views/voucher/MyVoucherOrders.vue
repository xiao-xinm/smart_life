<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import router from '@/router'
import { cancelVoucherOrder, getMyVoucherOrders } from '@/api/voucher'
import { ArrowLeft, Location, Shop, Tickets } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const route = useRoute()
const loading = ref(false)
const orders = ref([])

const highlightOrderId = computed(() => String(route.query.orderId || ''))

onMounted(() => {
  loadOrders()
})

const loadOrders = async () => {
  loading.value = true
  try {
    const res = await getMyVoucherOrders()
    orders.value = Array.isArray(res?.data) ? res.data : []
  } catch (error) {
    ElMessage.error('加载我的券失败')
  } finally {
    loading.value = false
  }
}

const openShop = (shopId) => {
  if (shopId) {
    router.push(`/shopDetail/${shopId}`)
  }
}

const cancelOrder = async (item) => {
  if (!item?.voucherId) {
    return
  }
  try {
    const res = await cancelVoucherOrder(item.voucherId)
    if (res?.success && String(res.data) === 'true') {
      ElMessage.success('已取消领取')
      await loadOrders()
      return
    }
    ElMessage.error(res?.errorMsg || '取消失败')
  } catch (error) {
    ElMessage.error('取消失败')
  }
}

const money = (value) => Number(value || 0)

const formatDate = (value) => {
  if (!value) {
    return '暂无时间'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return String(value).replace('T', ' ')
  }
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<template>
  <div class="orders-page">
    <header class="page-header">
      <button class="icon-btn" type="button" @click="router.back()">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <div>
        <h1>我的券包</h1>
        <p>已领取成功、可去店铺使用的优惠券</p>
      </div>
    </header>

    <section class="orders-panel">
      <div v-if="loading" class="empty-state">加载中...</div>

      <div v-else-if="orders.length" class="order-list">
        <article
          v-for="item in orders"
          :key="item.orderId"
          class="order-card"
          :class="{ highlight: highlightOrderId && String(item.orderId) === highlightOrderId }"
        >
          <div class="card-head">
            <div>
              <h2>{{ item.shopName || '未知店铺' }}</h2>
              <p>
                <el-icon><Location /></el-icon>
                {{ item.shopArea || item.shopAddress || '暂无地址' }}
              </p>
            </div>
            <span>{{ item.orderStatusText || '可使用' }}</span>
          </div>

          <div class="voucher-box">
            <div>
              <div class="voucher-title">
                <el-icon><Tickets /></el-icon>
                {{ item.voucherTitle || '优惠券' }}
              </div>
              <p>{{ item.voucherSubTitle || item.voucherRules || '到店消费时可使用' }}</p>
            </div>
            <div class="voucher-price">
              <span>￥{{ money(item.payValue) }}</span>
              <small>抵 ￥{{ money(item.actualValue) }}</small>
            </div>
          </div>

          <div class="meta-row">
            <span>订单号 {{ item.orderId }}</span>
            <span>{{ formatDate(item.createTime) }}</span>
          </div>

          <div class="action-row">
            <button class="secondary-btn" type="button" @click="cancelOrder(item)">
              取消领取
            </button>
            <button class="primary-btn" type="button" @click="openShop(item.shopId)">
              <el-icon><Shop /></el-icon>
              去店铺使用
            </button>
          </div>
        </article>
      </div>

      <div v-else class="empty-state">
        还没有已领取的券。抢券成功后，会在这里看到可使用的券。
      </div>
    </section>
  </div>
</template>

<style scoped>
.orders-page {
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
.card-head h2 {
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

.orders-panel {
  margin: 12px;
}

.order-list {
  display: grid;
  gap: 12px;
}

.order-card {
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.order-card.highlight {
  border-color: #2563eb;
  box-shadow: 0 10px 28px rgba(37, 99, 235, 0.16);
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.card-head h2 {
  font-size: 17px;
}

.card-head p {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #64748b;
  font-size: 12px;
  margin: 5px 0 0;
}

.card-head span {
  flex-shrink: 0;
  padding: 6px 10px;
  border-radius: 999px;
  background: #dcfce7;
  color: #15803d;
  font-size: 12px;
  font-weight: 800;
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

.meta-row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  color: #64748b;
  font-size: 12px;
}

.action-row {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 10px;
  margin-top: 12px;
}

.primary-btn,
.secondary-btn {
  height: 38px;
  border-radius: 8px;
  border: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-weight: 800;
}

.primary-btn {
  background: #111827;
  color: #fff;
}

.secondary-btn {
  background: #f1f5f9;
  color: #475569;
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
