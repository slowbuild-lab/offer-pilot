import { API_BASE } from './api'

export type CheckStatus = 'PASS' | 'WARN' | 'FAIL'

export interface PreflightCheck {
  id: string
  label: string
  status: CheckStatus
  hint: string
  action?: string
}

export interface PreflightResult {
  platform: string
  ready: boolean
  checks: PreflightCheck[]
  warnings: string[]
}

export interface PlatformMeta {
  name: string
  page: string
  start: string
  stop: string
}

/** 平台 → 页面与启停接口。新增平台时只改这里。 */
export const PLATFORM_META: Record<string, PlatformMeta> = {
  boss: { name: 'BOSS直聘', page: '/boss', start: '/api/boss/start', stop: '/api/boss/stop' },
  zhilian: { name: '智联招聘', page: '/zhilian', start: '/api/zhilian/start', stop: '/api/zhilian/stop' },
  liepin: { name: '猎聘', page: '/liepin', start: '/api/liepin/start', stop: '/api/liepin/stop' },
  '51job': { name: '前程无忧', page: '/51job', start: '/api/51job/start', stop: '/api/51job/stop' },
}

export function platformName(platform: string): string {
  return PLATFORM_META[platform]?.name || platform
}

/** 只读预检；失败返回 null，由调用方决定是否继续（不阻塞原有启动流程） */
export async function runPreflight(platform: string): Promise<PreflightResult | null> {
  try {
    const response = await fetch(`${API_BASE}/api/platforms/${platform}/preflight`, { method: 'POST' })
    if (!response.ok) return null
    return (await response.json()) as PreflightResult
  } catch {
    return null
  }
}
