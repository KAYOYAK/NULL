// Windows 上 `npm run build` 用不了（脚本里是 `rm -rf`），这里补一条跨平台的：
// 清 dist、置 NODE_ENV=production、调本地 rspack 构建，产物给 Android 的 assets 用。
import { spawnSync } from 'node:child_process'
import { rmSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
rmSync(join(root, 'dist'), { recursive: true, force: true })

const env = { ...process.env, NODE_ENV: 'production' }
const win = process.platform === 'win32'
const bin = join(root, 'node_modules', '.bin', win ? 'rspack.CMD' : 'rspack')

// .CMD 不能直接 spawn，Windows 下经 cmd /c 调用；路径已绝对化，不依赖 cwd
const result = win
  ? spawnSync('cmd', ['/c', bin, 'build', '-c', 'rspack.config.ts'], { stdio: 'inherit', env, cwd: root })
  : spawnSync(bin, ['build', '-c', 'rspack.config.ts'], { stdio: 'inherit', env, cwd: root })

process.exit(result.status ?? 1)
