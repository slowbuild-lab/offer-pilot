export type BossFilters = { includeCompanies: string[]; descriptionInclude: string[]; descriptionExclude: string[] }
export type ContactPolicy = { hrCooldownDays: number | null }
export type RuleCheck = { code: string; label: string; passed: boolean; term: string; evidence: string; reason: string }
export const emptyBossFilters = (): BossFilters => ({ includeCompanies: [], descriptionInclude: [], descriptionExclude: [] })
export const emptyContactPolicy = (): ContactPolicy => ({ hrCooldownDays: null })
export function recentScreeningRange() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const part = (key: string) => parts.find(p => p.type === key)!.value
  const to = `${part('year')}-${part('month')}-${part('day')}`
  const first = new Date(`${to}T00:00:00Z`); first.setUTCDate(first.getUTCDate() - 6)
  return { from: first.toISOString().slice(0, 10), to, result: '' }
}
export function safeBossLink(value: string) {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === 'www.zhipin.com' && !url.username && !url.password && !url.port ? url.href : null
  } catch { return null }
}
