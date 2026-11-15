import { normalizeSession } from './branches.js'
import type { Message, Session } from './types.js'

const collectSubtree = (messages: Message[], rootId: string): Set<string> => {
  const childrenOf = new Map<string | null | undefined, Message[]>()
  for (const message of messages) {
    const list = childrenOf.get(message.parentId)
    if (list) {
      list.push(message)
    } else {
      childrenOf.set(message.parentId, [message])
    }
  }
  const doomed = new Set<string>()
  const walk = (id: string) => {
    doomed.add(id)
    for (const child of childrenOf.get(id) ?? []) {
      walk(child.id)
    }
  }
  walk(rootId)
  return doomed
}

/**
 * Removes one message and everything hanging below it on that branch.
 *
 * The tree is what makes this more than a filter: the parent keeps a pointer to
 * whichever child is shown, and deleting the shown child has to move that pointer
 * to a surviving sibling — otherwise the conversation breaks off mid-air, and a
 * dangling selectedChildId is exactly the kind of state that only shows up on the
 * next reload.
 *
 * Deleting a question therefore deletes its answer with it (the answer is a
 * descendant), which is what the native "delete this exchange" did; deleting an
 * answer leaves the question, so it can be asked again.
 *
 * Returns the original session when the message is not part of it.
 */
export const deleteMessage = (original: Session, messageId: string): Session => {
  const session = normalizeSession(original)
  const target = session.messages.find((message) => message.id === messageId)
  if (!target) {
    return original
  }
  const doomed = collectSubtree(session.messages, messageId)
  const survivors = session.messages.filter((message) => !doomed.has(message.id))

  const sibling = survivors.find((message) => message.parentId === target.parentId)
  const messages = survivors.map((message) =>
    message.id === target.parentId && message.selectedChildId === messageId
      ? { ...message, selectedChildId: sibling?.id }
      : message
  )

  return {
    ...session,
    messages,
    rootMessageId:
      target.parentId === null ? (sibling?.id ?? null) : session.rootMessageId,
    updatedAt: Date.now(),
  }
}
