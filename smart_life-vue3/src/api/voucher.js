import request from '@/utils/request'
export const getVoucherList = (shopId) => request.get('/voucher/list/' + shopId)

// 获取秒杀访问令牌
export const issueSeckillAccessToken = (id) =>
  request.get('/voucher-order/seckill/token/' + id)

// 携带令牌进行秒杀下单
export const seckillVoucher = (id, accessToken) =>
  request.post(`/voucher-order/seckill/${id}`,
    null,
    { params: { accessToken } }
  )
// 轮询查询秒杀订单是否生成
export const getSeckillOrderId = (orderId) =>
  request.post('/voucher-order/get/seckill/voucher/order-id', {
    orderId: String(orderId)
  })

// 进入页面或秒杀成功后，查询用户是否已购买该优惠券
export const getVoucherOrderIdByVoucherId = (voucherId) =>
  request.post('/voucher-order/get/seckill/voucher/order-id/by/voucher-id', {
    voucherId: String(voucherId)
  })

// 取消已领取的优惠券
export const cancelVoucherOrder = (voucherId) =>
  request.post('/voucher-order/cancel', {
    voucherId: String(voucherId)
  })

export const getMyVoucherOrders = () => request.get('/voucher-order/my')

// 订阅开抢提醒
export const subscribeVoucher = (voucherId) =>
  request.post('/voucher/subscribe', {
    voucherId: String(voucherId)
  })

// 取消订阅（从队列移除）
export const unsubscribeVoucher = (voucherId) =>
  request.post('/voucher/unsubscribe', {
    voucherId: String(voucherId)
  })

// 查询当前用户订阅状态（单个券）：0 未订阅或已取消；1 已订阅；2 已领取
export const getSubscribeStatus = (voucherId) =>
  request.post('/voucher/get/subscribe/status', {
    voucherId: String(voucherId)
  })

// 批量查询订阅状态（页面初始化使用）
export const getSubscribeStatusBatch = (voucherIdList) =>
  request.post('/voucher/get/subscribe/status/batch', {
    voucherIdList: (voucherIdList || []).map((id) => String(id))
  })

export const getSubscribeCenter = () => request.get('/voucher/subscribe/list')

export const getNotifications = () => request.get('/voucher/notifications')

export const getUnreadNotifications = () => request.get('/voucher/notifications/unread')

export const markNotificationsRead = () => request.post('/voucher/notifications/read')
