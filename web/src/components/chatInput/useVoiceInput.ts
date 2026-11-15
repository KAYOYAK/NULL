import { useCallback, useEffect, useRef, useState } from 'react'
import type { TranslationKey, TranslationMessage } from '../../i18n/index.js'
import { transcribe } from '../../services/api/speech.js'
import { hasLocalEngine, localListenStart, localListenStop } from '../../services/api/local.js'
import { startRecording, type Recording } from '../../services/speech/recorder.js'
import { useSettings } from '../../services/settings.js'
import { showToast } from '../base/toastState.js'


/** 系统识别器的返回码 → 界面文案（本机模式专用，云端那条路用自己的错误类型） */
const VOICE_ERROR_KEYS: Record<string, TranslationKey> = {
  voice_unavailable: 'voiceLocalUnavailable',
  voice_permission: 'voiceLocalPermission',
  voice_needs_network: 'voiceLocalNeedsNetwork',
  voice_nothing_heard: 'voiceLocalNothingHeard',
  voice_failed: 'voiceLocalFailed',
}

export type VoiceInputStatus = 'idle' | 'recording' | 'transcribing'

/**
 * 录音依赖 getUserMedia，只在安全上下文可用（HTTPS、localhost、file://）。
 * 普通 HTTP 页面上直接禁用入口，避免点击后必然失败。
 */
const AVAILABLE = typeof window === 'undefined' || window.isSecureContext

const describeRecordingError = (error: unknown): TranslationMessage => {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'SecurityError') {
      return { key: 'voiceInputPermissionDenied' }
    }
    if (error.name === 'NotFoundError') {
      return { key: 'voiceInputNoDevice' }
    }
  }
  return { key: 'voiceInputFailed' }
}

/**
 * 语音输入（oneshot）：点击开始录音，再次点击结束并转写，结果交给 onText 填入输入框。
 * 转写中再次点击取消。录满 120 秒自动结束。
 */
export const useVoiceInput = (onText: (text: string) => void) => {
  const [status, setStatus] = useState<VoiceInputStatus>('idle')
  /** 本次录音已录秒数，只在录音中递增 */
  const [seconds, setSeconds] = useState(0)
  const recordingRef = useRef<Recording | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const operationRef = useRef(0)
  const onTextRef = useRef(onText)
  onTextRef.current = onText

  const cancelOperation = useCallback(() => {
    operationRef.current++
    recordingRef.current?.cancel()
    recordingRef.current = null
    controllerRef.current?.abort()
    controllerRef.current = null
  }, [])

  const cancel = useCallback(() => {
    cancelOperation()
    setStatus('idle')
  }, [cancelOperation])

  const finish = useCallback(async () => {
    const recording = recordingRef.current
    if (!recording) {
      // 仍在等待麦克风授权时也允许取消；迟到的录音对象会立即释放。
      cancel()
      return
    }
    const operation = operationRef.current
    recordingRef.current = null
    let controller: AbortController | null = null
    try {
      let audio: Blob | null
      try {
        audio = await recording.stop()
      } finally {
        recording.cancel()
      }
      if (operationRef.current !== operation || !audio) {
        return
      }
      controller = new AbortController()
      controllerRef.current = controller
      setStatus('transcribing')
      const text = await transcribe(useSettings.getState().apiKey ?? '', audio, controller.signal)
      if (controller.signal.aborted || operationRef.current !== operation) {
        return
      }
      if (text.trim()) {
        onTextRef.current(text.trim())
      } else {
        showToast({ key: 'voiceInputEmpty' })
      }
    } catch {
      if (operationRef.current === operation && !controller?.signal.aborted) {
        showToast({ key: 'voiceInputFailed' })
      }
    } finally {
      if (operationRef.current === operation) {
        controllerRef.current = null
        setStatus('idle')
      }
    }
  }, [cancel])

  const toggleLocal = useCallback(() => {
    if (status === 'recording') {
      localListenStop()
      setStatus('transcribing')
      return
    }
    if (status === 'transcribing') {
      localListenStop()
      setStatus('idle')
      return
    }
    const code = localListenStart()
    if (code === 'ok' || code === 'voice_requesting') {
      setStatus('recording')
      return
    }
    showToast({ key: VOICE_ERROR_KEYS[code] ?? 'voiceLocalFailed' })
  }, [status])

  const toggle = useCallback(async () => {
    if (hasLocalEngine()) {
      toggleLocal()
      return
    }
    if (!AVAILABLE) {
      return
    }
    if (status === 'recording') {
      await finish()
      return
    }
    if (status === 'transcribing') {
      cancel()
      return
    }
    const operation = ++operationRef.current
    setStatus('recording')
    try {
      const recording = await startRecording(() => {
        if (operationRef.current === operation) {
          showToast({ key: 'voiceInputLimitReached' })
          void finish()
        }
      })
      if (operationRef.current !== operation) {
        recording.cancel()
        return
      }
      recordingRef.current = recording
    } catch (error) {
      if (operationRef.current === operation) {
        setStatus('idle')
        showToast(describeRecordingError(error))
      }
    }
  }, [status, finish, cancel, toggleLocal])

  useEffect(() => {
    if (status !== 'recording') {
      return
    }
    const startedAt = Date.now()
    setSeconds(0)
    // 半秒刷新一次，让显示的秒数与实际录音时长保持一致
    const timer = setInterval(() => {
      setSeconds(Math.floor((Date.now() - startedAt) / 1000))
    }, 500)
    return () => clearInterval(timer)
  }, [status])

  // 本机引擎：麦克风交给系统的识别器，页面只收文本。
  // 录音与频谱属于云端那条路（它要上传 PCM），本机模式下没有等价物。
  useEffect(() => {
    if (!hasLocalEngine()) {
      return
    }
    const globals = window as unknown as {
      __nullVoice?: (kind: string, text: string) => void
    }
    globals.__nullVoice = (kind, text) => {
      if (kind === 'partial') {
        return
      }
      if (kind === 'final') {
        setStatus('idle')
        if (text.trim()) {
          onTextRef.current(text.trim())
        } else {
          showToast({ key: 'voiceInputEmpty' })
        }
        return
      }
      if (kind === 'error') {
        setStatus('idle')
        showToast({ key: VOICE_ERROR_KEYS[text] ?? 'voiceLocalFailed' })
      }
    }
    return () => {
      localListenStop()
      delete globals.__nullVoice
    }
  }, [])


  useEffect(() => cancelOperation, [cancelOperation])

  /** 当前录音的频谱分析节点，未录音时为 null */
  const getAnalyser = useCallback(() => recordingRef.current?.analyser ?? null, [])

  return { status, seconds, getAnalyser, toggle, cancel, available: AVAILABLE }
}
