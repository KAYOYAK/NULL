import { useContextMeter } from '../../services/context.js'
import { useTranslate } from '../../i18n/index.js'
import styles from './ContextMeter.module.css'

/**
 * How full the model's context is.
 *
 * The number is what the runtime reported after the last turn, not an estimate:
 * a local model silently stops reading the beginning of a long conversation, and
 * a user who cannot see that concludes the model got worse.
 */
export const ContextMeter = () => {
  const t = useTranslate()
  const used = useContextMeter((state) => state.used)
  const window = useContextMeter((state) => state.window)
  if (used === null || window === null || window <= 0) {
    return null
  }
  const ratio = Math.min(1, used / window)
  return (
    <div className={styles.meter} title={t('contextMeterTitle')}>
      <div className={styles.track}>
        <div
          className={ratio > 0.85 ? styles.fillHigh : styles.fill}
          style={{ width: `${Math.round(ratio * 100)}%` }}
        />
      </div>
      <span className={styles.label}>{t('contextMeter', { used, window })}</span>
    </div>
  )
}
