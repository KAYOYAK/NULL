import { translate } from '../../i18n/index.js'
import { monthKey } from '../../utils/format.js'
import type { SessionSummary } from './types.js'

const DAY = 86_400_000

const startOfDay = (time: number) => new Date(time).setHours(0, 0, 0, 0)

/** 分组：今天 / 昨天 / 7 天内 / 30 天内 / 按月 */
export const groupSessions = (sessions: SessionSummary[], now: number, t = translate) => {
  const groups: { key: string; label: string; sessions: SessionSummary[] }[] = []

  // 置顶与归档先摘出来：置顶永远在最前，归档收在最后，其余按时间分组。
  // chat-demo 原本只有时间分组，这两种是 NULL 的会话列表本来就有的。
  const pinned = sessions.filter((session) => session.pinnedAt !== undefined)
  if (pinned.length > 0) {
    groups.push({
      key: 'pinned',
      label: t('sessionGroupPinned'),
      sessions: [...pinned].sort((a, b) => (b.pinnedAt ?? 0) - (a.pinnedAt ?? 0)),
    })
  }
  for (const session of sessions.filter(
    (item) => item.pinnedAt === undefined && item.archivedAt === undefined
  )) {
    const diff = Math.floor((now - startOfDay(session.updatedAt)) / DAY)
    const key =
      diff <= 0
        ? 'sessionHistoryTimelineLabelToday'
        : diff <= 1
          ? 'sessionHistoryTimelineLabelYesterday'
          : diff < 7
            ? 'sessionHistoryTimelineLabel7days'
            : diff < 30
              ? 'sessionHistoryTimelineLabel30days'
              : null
    const groupKey = key ?? monthKey(session.updatedAt) ?? ''
    const last = groups.at(-1)
    if (last?.key === groupKey) {
      last.sessions.push(session)
    } else {
      groups.push({ key: groupKey, label: key ? t(key) : groupKey, sessions: [session] })
    }
  }
  const archived = sessions.filter((session) => session.archivedAt !== undefined)
  if (archived.length > 0) {
    groups.push({
      key: 'archived',
      label: t('sessionGroupArchived'),
      sessions: [...archived].sort((a, b) => (b.archivedAt ?? 0) - (a.archivedAt ?? 0)),
    })
  }
  return groups
}
