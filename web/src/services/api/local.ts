import { readLocalImage, isLocalImageId } from '../localImageStore.js'
import { translate } from '../../i18n/index.js'
import type { TranslationKey } from '../../i18n/index.js'
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
  /** 打开原生界面：模型管理、关于这些 chat-demo 做不了的 */
  openNative?: (screen: string) => boolean
  /** 把一段文字交给系统分享 */
  share?: (text: string) => boolean
  /** 主题："system" | "light" | "dark" */
  theme?: () => string
  setTheme?: (theme: string) => boolean
  /** 系统的语音识别；返回 ok / voice_requesting / 错误码 */
  startListening?: () => string
  stopListening?: () => void
  available?: () => string
  probe?: () => string
  start?: (request: string) => string
  abort?: (id: string) => void
  /** 设备自带 TTS，离线可用；返回 "ok" 或不可用原因 */
  speak?: (text: string) => string
  stopSpeak?: () => void
}

type Emit = (id: string, kind: string, payload: string) => void

/**
 * 桥回的是机器码，不是句子：同一段文字在 Android 侧属于 strings.xml，
 * 在这套前端属于 i18n，两边各译各的，不互相搬运文案。
 */
const LOCAL_ERROR_KEYS: Record<string, TranslationKey> = {
  voice_unavailable: 'voiceLocalUnavailable',
  voice_permission: 'voiceLocalPermission',
  voice_needs_network: 'voiceLocalNeedsNetwork',
  voice_nothing_heard: 'voiceLocalNothingHeard',
  voice_failed: 'voiceLocalFailed',
  loading: 'localModelLoading',
  missing_model: 'localModelMissing',
  tts_unavailable: 'localTtsUnavailable',
  tts_initialising: 'localTtsInitialising',
  empty_prompt: 'localEmptyPrompt',
  generation_failed: 'localGenerationFailed',
}

export const localErrorText = (code: string): string => {
  const key = LOCAL_ERROR_KEYS[code]
  return key ? translate(key) : code
}

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


/** 会话标题：第一行、压掉多余空白、最长 24 个字符，超出以省略号收尾 */
const titleFromPrompt = (prompt: string): string => {
  const line = prompt.trim().split('\n').find((part) => part.trim() !== '') ?? ''
  const flat = line.replace(/\s+/g, ' ').trim()
  return flat.length > 24 ? `${flat.slice(0, 24)}…` : flat
}


/**
 * 打开网页界面时模型可能还在加载（Compose 那边一进来就开始加载了）。
 * 等它一小会儿再发，比让用户看到「还没有加载模型」更接近事实——那时它确实正在加载。
 */
const waitWhileLoading = async (signal: AbortSignal, timeoutMs = 20000): Promise<void> => {
  const deadline = Date.now() + timeoutMs
  // `idle` counts too: the Compose screen starts loading the model when the app
  // opens, and a page opened in the same breath can arrive before that begins.
  while (!signal.aborted && localEngineState() !== 'ready' && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
}


/** 本轮用户消息里的图片块：本机模式下它们引用的是内存里的字节，不是 file_id */
const collectImageBlocks = async (messages: MessageParam[]): Promise<string[]> => {
  const lastUser = [...messages].reverse().find((message) => message.role === 'user')
  if (!lastUser || typeof lastUser.content === 'string') {
    return []
  }
  const out: string[] = []
  for (const block of lastUser.content) {
    if (block.type !== 'image' || block.source.type !== 'file') {
      continue
    }
    const id = block.source.file_id
    if (!isLocalImageId(id)) {
      continue
    }
    const data = await readLocalImage(id)
    if (data) {
      out.push(data)
    }
  }
  return out
}


/** 本机语音输入的入口；返回的码由 localErrorText 翻成界面语言 */
export const localListenStart = (): string => {
  const local = bridge()
  if (!local?.startListening) {
    return 'voice_unavailable'
  }
  try {
    return local.startListening()
  } catch (error) {
    return error instanceof Error ? 'voice_failed' : 'voice_failed'
  }
}

export const localListenStop = () => {
  try {
    bridge()?.stopListening?.()
  } catch {
    // 停止失败不影响调用方
  }
}


/**
 * Opens one of NULL's own screens, which stay native because the page cannot
 * provide them: model downloads and verification, the licence list, the app lock.
 */
export const openNative = (
  screen: 'models' | 'about' | 'settings' | 'history' | 'chat',
): boolean => {
  try {
    return bridge()?.openNative?.(screen) ?? false
  } catch {
    return false
  }
}


/**
 * Hands one reply to the system share sheet.
 *
 * chat-demo copies; it does not share. Sharing was a native capability, and a
 * capability that only exists in a screen nobody opens any more is a capability
 * that has been removed.
 */
export const shareText = (text: string): boolean => {
  if (!text.trim()) {
    return false
  }
  try {
    return bridge()?.share?.(text) ?? false
  } catch {
    return false
  }
}


/** The app's theme preference, shared with the native screens. */
export const nativeTheme = (): 'system' | 'light' | 'dark' | null => {
  try {
    const value = bridge()?.theme?.()
    return value === 'light' || value === 'dark' || value === 'system' ? value : null
  } catch {
    return null
  }
}

export const setNativeTheme = (theme: 'system' | 'light' | 'dark'): void => {
  try {
    bridge()?.setTheme?.(theme)
  } catch {
    // 主题写不进去只影响记忆，下一次打开仍按系统来
  }
}

export async function* streamLocalMessage(
  body: CreateMessageRequest,
  signal: AbortSignal
): AsyncGenerator<MessageStreamEvent> {
  const local = bridge()
  if (!local?.start) {
    throw new Error('本机引擎不可用')
  }

  const wantsTitle = body.title?.enabled === true
  const turns = toLocalTurns(body.messages)
  const lastIndex = turns.length - 1
  if (lastIndex < 0 || !turns[lastIndex].fromUser) {
    throw new Error('没有可发送的问题')
  }

  const prompt = turns[lastIndex].text
  const images = await collectImageBlocks(body.messages)

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
        case 'done': {
          if (textOpen) {
            queue.push({ type: 'content_block_stop', index: 0 })
            textOpen = false
          }
          // The cloud used to name the session. Locally the only name available is
          // the question, which is the same thing a reader would have written.
          const title = wantsTitle ? titleFromPrompt(prompt) : ''
          queue.push({
            type: 'message_delta',
            delta: {
              stop_reason: data.stopReason === 'max_tokens' ? 'max_tokens' : 'end_turn',
              stop_sequence: null,
              ...(title ? { title } : {}),
            },
          })
          queue.push({ type: 'message_stop' })
          finished = true
          break
        }
        case 'error': {
          const detail = typeof data.detail === 'string' ? data.detail : ''
          const text = localErrorText(String(data.message ?? ''))
          failure = new Error(detail ? `${text}（${detail}）` : text)
          finished = true
          break
        }
        default:
          break
      }
    } finally {
      notify()
    }
  }

  await waitWhileLoading(signal)
  if (signal.aborted) {
    return
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
      prompt,
      images,
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
    return { ok: false, reason: localErrorText('tts_unavailable') }
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
