export const platformNames = { boss: 'BOSS 直聘', zhilian: '智联招聘', '51job': '前程无忧', liepin: '猎聘' } as const
export type PacingPlatform = keyof typeof platformNames
export const pacingDefaults = { minIntervalSeconds: 120, extraDelaySeconds: 60, readIntervalSeconds: 10, pageIntervalSeconds: 30, startupSeconds: 15, restEvery: 10, continuousMinutes: 30, restMinutes: 15, dailyLimit: 20, detailBudget: 200, actionIntervalSeconds: 5 }
export type PacingPolicy = typeof pacingDefaults
export type PacingScope = {
  version: number
  policy: PacingPolicy
  pending_limit: number | null
  pending_day: string | null
  used: number
  effectiveLimit: number
  state: { next_delivery: number; next_read: number; next_list: number; start_until: number; rest_until: number; paused: string; not_before: number; read_day: string; read_count: number }
}
export type PacingSnapshot = {
  platforms: Record<PacingPlatform, PacingScope>
  global: { version: number; policy: { minIntervalSeconds: number; dailyLimit: number }; used: number; pending_limit: number | null; pending_day: string | null }
  serverTime: number
  day: string
}
