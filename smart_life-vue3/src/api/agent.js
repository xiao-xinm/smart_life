import request from '@/utils/request'
import { baseURL } from '@/utils/request'
import { useUserStore } from '@/stores'

export const agentChat = (message, context = {}) =>
  request.post('/agent/chat', {
    message,
    context
  }, {
    timeout: 95000
  })

export const subscribeVoucherByAgent = (voucherId) =>
  request.post(`/agent/tools/voucher/${voucherId}/subscribe`)

export const agentChatStream = async (message, context = {}, handlers = {}) => {
  const userStore = useUserStore()
  const response = await fetch(`${baseURL}/agent/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(userStore.token ? { Authorization: userStore.token } : {})
    },
    body: JSON.stringify({ message, context })
  })
  if (!response.ok || !response.body) {
    throw new Error('Agent stream request failed')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const dispatchEvent = (block) => {
    const lines = block.split('\n')
    const event = lines.find((line) => line.startsWith('event:'))?.slice(6).trim() || 'message'
    const dataText = lines
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim())
      .join('\n')
    if (!dataText) {
      return
    }
    const data = JSON.parse(dataText)
    handlers[event]?.(data)
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() || ''
    blocks.forEach(dispatchEvent)
  }
  if (buffer.trim()) {
    dispatchEvent(buffer)
  }
}
