import { useState } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { removeMessage } from '../../services/chat/store.js'
import { Button } from '../base/Button.js'
import { IconButton } from '../base/IconButton.js'
import { IconTrashOutline16 } from '../base/icons.js'
import { Modal } from '../base/Modal.js'

/**
 * Deletes one message, or the question and its answer together.
 *
 * A confirmation rather than a straight action: deleting a question takes the
 * answers below it, and there is no undo — the same reasoning the session list
 * already uses for its own delete.
 *
 * [exchange] picks the wording. The behaviour is the same: the message and its
 * subtree, which for a question is its answer chain.
 */
export const DeleteMessageButton = ({
  sessionId,
  messageId,
  exchange,
}: {
  sessionId: string
  messageId: string
  exchange: boolean
}) => {
  const t = useTranslate()
  const [open, setOpen] = useState(false)
  const label = exchange ? t('deleteExchange') : t('deleteMessage')
  return (
    <>
      <IconButton
        icon={<IconTrashOutline16 />}
        tooltip={label}
        tooltipPlacement="bottom"
        onClick={() => setOpen(true)}
      />
      {open ? (
        <Modal
          title={label}
          onClose={() => setOpen(false)}
          footer={
            <>
              <Button variant="outlined" onClick={() => setOpen(false)}>
                {t('cancel')}
              </Button>
              <Button
                variant="danger"
                onClick={() => {
                  setOpen(false)
                  removeMessage(sessionId, messageId)
                }}
              >
                {t('delete')}
              </Button>
            </>
          }
        >
          <p>{t(exchange ? 'deleteExchangeBody' : 'deleteMessageBody')}</p>
        </Modal>
      ) : null}
    </>
  )
}
