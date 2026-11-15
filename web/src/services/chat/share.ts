import { getVisibleMessages } from './branches.js'
import type { AssistantMessage, Message, Session, UserMessage } from './types.js'

const userText = (message: UserMessage): string => message.content.trim()

const assistantText = (message: AssistantMessage): string =>
  message.blocks
    .filter((block) => block.type === 'text')
    .map((block) => block.text.trim())
    .filter((text) => text.length > 0)
    .join('\n\n')

const stamp = (time: number): string => new Date(time).toLocaleString()

const speaker = (message: Message): string => (message.role === 'user' ? '你' : '模型')

/**
 * A conversation as one block of text, for the share sheet.
 *
 * The native app could hand a whole conversation to another app; chat-demo could
 * only copy a single reply. This is that capability, rebuilt on the side that
 * survived: the same store, the same share sheet, no server involved.
 */
export const sessionAsText = (session: Session): string => {
  const lines: string[] = [session.title]
  for (const message of getVisibleMessages(session)) {
    const body = message.role === 'user' ? userText(message) : assistantText(message)
    if (!body) {
      continue
    }
    lines.push('', `${speaker(message)}（${stamp(message.createdAt)}）`, body)
  }
  return lines.join('\n').trim()
}

/**
 * The same conversation as Markdown.
 *
 * Written the way the native export was: model id, token count and elapsed time
 * per reply, because a transcript without them cannot be compared to another one
 * later, and comparing two models is the reason this app exists.
 */
export const sessionAsMarkdown = (session: Session): string => {
  const lines: string[] = [`# ${session.title}`, '', `${stamp(session.createdAt)} 起`]
  for (const message of getVisibleMessages(session)) {
    if (message.role === 'user') {
      const body = userText(message)
      if (body) {
        lines.push('', `## 你 · ${stamp(message.createdAt)}`, '', body)
      }
      continue
    }
    const body = assistantText(message)
    if (!body) {
      continue
    }
    const facts: string[] = []
    if (message.model) {
      facts.push(message.model)
    }
    if (message.usage?.output_tokens) {
      facts.push(`${message.usage.output_tokens} tok`)
    }
    lines.push('', `## 模型 · ${stamp(message.createdAt)}`, '')
    if (facts.length > 0) {
      lines.push(`*${facts.join(' · ')}*`, '')
    }
    lines.push(body)
  }
  lines.push('', '---', '', '由 NULL 在本机导出；对话没有离开这台设备。')
  return lines.join('\n')
}
