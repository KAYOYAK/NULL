import type { CreateMessageRequest, MessageParam, MessageStreamEvent } from './types.js'

/**
 * 本机引擎桥接。
 *
 * Android 侧用 `addJavascriptInterface` 注入 `window.NullLocal`，把请求交给应用里
 * 已经加载好的 LiteRT-LM 引擎；浏览器里没有这个对象，`hasLocalEngine()` 为 false，
 * 上层会继续走原来的云端传输层。两条路径产出的都是 `MessageStreamEvent`，所以
 * 回复渲染、分支、历史、导出都不需要知道消息是谁生成的。
 *
 * 协议（Kotlin 侧实现）：
 *   available() -> "1" | "0"
 *   probe()     -> "ready" | "loading" | "missing:<原因>"
 *   start(requestJson) -> requestId
 *   abort(requestId) -> void
 *   引擎通过 window.__nullLocalEmit(requestId, kind, payloadJson) 回推事件，
 *   kind ∈ {"start","text","done","error"}。
 */
type NullLocalBridge = {
  available?: () => string
  probe?: () => string
  start?: (request: string) => string
  abort?: (id: string) => void
  /** 设备自带 TTS，离线可用；返回 "ok" 或不可用原因 */
  speak?: (text: string) => string
  stopSpeak?: () => void
}

type Emit = (id: string, kind: string, payload: string) => void

const bridge = (): NullLocalBridge | undefined =>
  (globalThis as { NullLocal?: NullLocalBridge }).NullLocal

/** 是否有可用的本机引擎；异常一律当作没有，避免把 UI 卡在等待里 */
export const hasLocalEngine = (): boolean => {
  try {
    return bridge()?.available?.() === '1'
  } catch {
    return false
  }
}

/** 引擎状态：ready / loading / missing:<原因> / offline */
export const localEngineState = (): string => {
  try {
    return bridge()?.probe?.() ?? 'offline'
  } catch (error) {
    return `error:${error instanceof Error ? error.message : String(error)}`
  }
}

const textOf = (content: MessageParam['content']): string =>
  typeof content === 'string'
    ? content
    : content
        .filter((block): block is { type: 'text'; text: string } => block.type === 'text')
        .map((block) => block.text)
        .join('\n')

/**
 * 把 Anthropic 请求转成本机引擎能吃的形状：历史轮次 + 本轮问题。
 * `latest_reminder` 是服务端模板注入的上下文，本机引擎不认，直接丢掉。
 */
export const toLocalTurns = (messages: MessageParam[]) =>
  messages
    .filter((message) => message.role !== 'latest_reminder')
    .map((message) => ({ fromUser: message.role === 'user', text: textOf(message.content) }))
    .filter((turn) => turn.text.trim() !== '')

export async function* streamLocalMessage(
  body: CreateMessageRequest,
  signal: AbortSignal
): AsyncGenerator<MessageStreamEvent> {
  const local = bridge()
  if (!local?.start) {
    throw new Error('本机引擎不可用')
  }

  const turns = toLocalTurns(body.messages)
  const lastIndex = turns.length - 1
  if (lastIndex < 0 || !turns[lastIndex].fromUser) {
    throw new Error('没有可发送的问题')
  }

  const queue: MessageStreamEvent[] = []
  let failure: Error | null = null
  let finished = false
  let wake: (() => void) | null = null
  let requestId = ''
  let textOpen = false

  const notify = () => {
    const resolve = wake
    wake = null
    resolve?.()
  }

  const emit: Emit = (id, kind, payload) => {
    if (id !== requestId) {
      return
    }
    try {
      const data = payload ? (JSON.parse(payload) as Record<string, unknown>) : {}
      switch (kind) {
        case 'start':
          queue.push({
            type: 'message_start',
            message: { id, model: String(data.model ?? 'local') },
          })
          queue.push({
            type: 'content_block_start',
            index: 0,
            content_block: { type: 'text', text: '' },
          })
          textOpen = true
          break
        case 'text': {
          const text = String(data.text ?? '')
          if (text) {
            queue.push({
              type: 'content_block_delta',
              index: 0,
              delta: { type: 'text_delta', text },
            })
          }
          break
        }
        case 'done':
          if (textOpen) {
            queue.push({ type: 'content_block_stop', index: 0 })
            textOpen = false
          }
          queue.push({
            type: 'message_delta',
            delta: {
              stop_reason: data.stopReason === 'max_tokens' ? 'max_tokens' : 'end_turn',
              stop_sequence: null,
            },
          })
          queue.push({ type: 'message_stop' })
          finished = true
          break
        case 'error':
          failure = new Error(String(data.message ?? '本机生成失败'))
          finished = true
          break
        default:
          break
      }
    } finally {
      notify()
    }
  }

  const globals = globalThis as { __nullLocalEmit?: Emit }
  globals.__nullLocalEmit = emit

  const onAbort = () => {
    if (requestId) {
      local.abort?.(requestId)
    }
    finished = true
    notify()
  }
  signal.addEventListener('abort', onAbort, { once: true })

  requestId = local.start(
    JSON.stringify({
      // 上游把「你是 DeepSeek」写进了 system，这是云端那个助手的人设；
      // 本机跑的是设备上加载的模型，人设由 LlmEngine.load 的 systemPrompt 决定，
      // 这里传空，避免模型自报成另一个产品。
      system: "",
      history: turns.slice(0, lastIndex),
      prompt: turns[lastIndex].text,
      maxTokens: 1024,
      thinking: body.thinking?.type === 'enabled',
    })
  )

  try {
    while (true) {
      if (queue.length > 0) {
        yield queue.shift() as MessageStreamEvent
        continue
      }
      if (finished) {
        if (failure) {
          throw failure
        }
        return
      }
      // 单线程：从判断队列为空到赋 wake 之间不会插入引擎事件，所以这里不会漏唤醒。
      await new Promise<void>((resolve) => {
        wake = resolve
      })
    }
  } finally {
    signal.removeEventListener('abort', onAbort)
    if (globals.__nullLocalEmit === emit) {
      delete globals.__nullLocalEmit
    }
  }
}

/** 朗读走设备自带 TTS（离线），与云端合成无关 */
export const localSpeak = (text: string): { ok: boolean; reason: string } => {
  const local = bridge()
  if (!local?.speak) {
    return { ok: false, reason: '本机没有可用的语音合成' }
  }
  try {
    const result = local.speak(text)
    return result === 'ok' ? { ok: true, reason: '' } : { ok: false, reason: result }
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : String(error) }
  }
}

export const localStopSpeaking = () => {
  try {
    bridge()?.stopSpeak?.()
  } catch {
    // 停止失败不影响调用方
  }
}
