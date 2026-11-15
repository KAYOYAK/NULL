import clsx from 'clsx'
import { memo, useState } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { IconEditOutline16, IconEllipsisOutline16, IconTrashOutline16 } from '../base/icons.js'
import { renameSession, selectSession } from '../../services/chat/store.js'
import { loadSession } from '../../services/chat/db.js'
import { sessionAsMarkdown, sessionAsText } from '../../services/chat/share.js'
import { hasLocalEngine, shareText } from '../../services/api/local.js'
import type { SessionSummary } from '../../services/chat/types.js'
import { IconButton } from '../base/IconButton.js'
import { Menu } from '../base/Menu.js'
import { closeMobileSider } from './mobileSiderState.js'
import styles from './SessionItem.module.css'

export const SessionItem = memo(function SessionItem({
  session,
  active,
  onDelete,
}: {
  session: SessionSummary
  active: boolean
  onDelete: () => void
}) {
  const t = useTranslate()
  const [editing, setEditing] = useState(false)
  const displayTitle = session.title || t('untitledChat')

  if (editing) {
    const finish = (title: string) => {
      setEditing(false)
      if (title.trim() && title.trim() !== displayTitle) {
        renameSession(session.id, title.trim())
      }
    }
    return (
      <input
        className={styles.renameInput}
        defaultValue={displayTitle}
        autoFocus
        onFocus={(event) => event.target.select()}
        onBlur={(event) => finish(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
            finish(event.currentTarget.value)
          } else if (event.key === 'Escape') {
            setEditing(false)
          }
        }}
      />
    )
  }

  return (
    <div
      className={clsx(styles.item, active && styles.active)}
      onClick={() => {
        selectSession(session.id)
        closeMobileSider()
      }}
    >
      <div className={styles.title}>{displayTitle}</div>
      <div className={styles.action} onClick={(event) => event.stopPropagation()}>
        <Menu
          options={[
            ...(hasLocalEngine()
              ? [
                  {
                    key: 'share-text',
                    label: t('sessionItemMenuShareText'),
                    icon: <IconEditOutline16 />,
                    onSelect: () => {
                      void loadSession(session.id).then((full) => {
                        if (full) {
                          shareText(sessionAsText(full))
                        }
                      })
                    },
                  },
                  {
                    key: 'share-markdown',
                    label: t('sessionItemMenuExportMarkdown'),
                    icon: <IconEditOutline16 />,
                    onSelect: () => {
                      void loadSession(session.id).then((full) => {
                        if (full) {
                          shareText(sessionAsMarkdown(full))
                        }
                      })
                    },
                  },
                ]
              : []),
            {
              key: 'rename',
              label: t('sessionItemMenuRenameOption'),
              icon: <IconEditOutline16 />,
              onSelect: () => setEditing(true),
            },
            {
              key: 'delete',
              label: t('delete'),
              icon: <IconTrashOutline16 />,
              danger: true,
              onSelect: onDelete,
            },
          ]}
        >
          {({ toggle }) => (
            <IconButton icon={<IconEllipsisOutline16 />} aria-label={t('more')} onClick={toggle} />
          )}
        </Menu>
      </div>
    </div>
  )
})
