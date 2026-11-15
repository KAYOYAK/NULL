import clsx from 'clsx'
import { useState } from 'react'
import { useIsMobile } from '../../app/viewport.js'
import { useTranslate } from '../../i18n/index.js'
import type { Session } from '../../services/chat/types.js'
import { ChatInput } from '../chatInput/ChatInput.js'
import { MessageList } from '../messages/MessageList.js'
import { ConversationSearch } from './ConversationSearch.js'
import { getVisibleMessages } from '../../services/chat/branches.js'
import { IconSearchOutline16 } from '../base/icons.js'
import { IconButton } from '../base/IconButton.js'
import { MobileHeader } from './MobileHeader.js'
import styles from './SessionView.module.css'

/** 会话页：标题栏（移动端为顶栏）+ 消息列表，输入框吸底 */
export const SessionView = ({
  session,
  streaming,
  siderCollapsed,
}: {
  session: Session
  streaming: boolean
  siderCollapsed: boolean
}) => {
  const t = useTranslate()
  const isMobile = useIsMobile()
  const title = session.title || t('untitledChat')
  // 查找的是这段对话里能读到的那些消息，也就是当前选中的分支。
  const [searching, setSearching] = useState(false)
  const visible = getVisibleMessages(session)
  return (
    <div className={styles.view}>
      {isMobile ? (
        <MobileHeader title={title} />
      ) : (
        <div className={styles.header}>
          <div className={clsx(styles.title, siderCollapsed && styles.withCollapsedSider)}>
            {title}
          </div>
          <IconButton
            icon={<IconSearchOutline16 />}
            tooltip={t('conversationSearchOpen')}
            onClick={() => setSearching(true)}
          />
        </div>
      )}
      {searching ? (
        <ConversationSearch messages={visible} onClose={() => setSearching(false)} />
      ) : null}
      <MessageList
        session={session}
        streaming={streaming}
        footer={
          <div className={styles.inputContainer}>
            <ChatInput sessionId={session.id} />
            <div className={styles.caveat}>{t('contentCaveat')}</div>
          </div>
        }
      />
    </div>
  )
}
