import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { IconCloseOutline16 } from '../base/icons.js'
import { IconButton } from '../base/IconButton.js'
import type { Message } from '../../services/chat/types.js'
import styles from './ConversationSearch.module.css'

/** The words inside a message, for matching. Thinking blocks are not searched. */
const bodyOf = (message: Message): string =>
  message.role === 'user'
    ? message.content
    : message.blocks
        .filter((block) => block.type === 'text')
        .map((block) => block.text)
        .join('\n')

/**
 * Finds text inside one conversation.
 *
 * chat-demo searches the session list, not the open transcript. The native app
 * could search the messages being read -- the case where a long reply is on screen
 * and the user wants to find the sentence they half remember -- and that is what
 * this restores: matches are counted, the transcript scrolls to the current one,
 * and the matched messages are outlined.
 */
export const ConversationSearch = ({
  messages,
  onClose,
}: {
  messages: Message[]
  onClose: () => void
}) => {
  const t = useTranslate()
  const [query, setQuery] = useState('')
  const [index, setIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const hits = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (needle.length === 0) {
      return []
    }
    return messages.filter((message) => bodyOf(message).toLowerCase().includes(needle))
  }, [messages, query])

  useEffect(() => {
    setIndex(0)
  }, [query])

  // Outline every match, and scroll the current one into view. The class is set on
  // the message's own DOM node by id, so no rendering path has to know about search.
  useEffect(() => {
    for (const message of messages) {
      document.getElementById(`msg-${message.id}`)?.classList.remove(styles.hit, styles.current)
    }
    hits.forEach((message, position) => {
      const node = document.getElementById(`msg-${message.id}`)
      node?.classList.add(styles.hit)
      if (position === index) {
        node?.classList.add(styles.current)
        node?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      }
    })
  }, [hits, index, messages])

  // Leaving search must not leave outlines behind.
  useEffect(
    () => () => {
      for (const node of document.querySelectorAll(`.${styles.hit}, .${styles.current}`)) {
        node.classList.remove(styles.hit, styles.current)
      }
    },
    []
  )

  const step = (delta: number) => {
    if (hits.length === 0) {
      return
    }
    setIndex((current) => (current + delta + hits.length) % hits.length)
  }

  return (
    <div className={styles.bar}>
      <input
        ref={inputRef}
        className={styles.input}
        value={query}
        placeholder={t('conversationSearchPlaceholder')}
        onChange={(event) => setQuery(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            step(event.shiftKey ? -1 : 1)
          } else if (event.key === 'Escape') {
            onClose()
          }
        }}
      />
      <span className={styles.count}>
        {query.trim().length === 0
          ? ''
          : hits.length === 0
            ? t('conversationSearchNone')
            : t('conversationSearchCount', { index: index + 1, total: hits.length })}
      </span>
      <button type="button" className={styles.step} onClick={() => step(-1)} disabled={hits.length === 0}>
        ↑
      </button>
      <button type="button" className={styles.step} onClick={() => step(1)} disabled={hits.length === 0}>
        ↓
      </button>
      <IconButton icon={<IconCloseOutline16 />} tooltip={t('close')} onClick={onClose} />
    </div>
  )
}
