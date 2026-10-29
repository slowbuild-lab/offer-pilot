'use client'

import { useCallback, useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'
import { pacingDefaults, platformNames, type PacingPlatform, type PacingSnapshot } from '@/lib/pacing'
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const fields: { key: keyof typeof pacingDefaults; name: string; min: number; max: number }[] = [
  { key: 'minIntervalSeconds', name: '投递最小间隔（秒）', min: 30, max: 86400 },
  { key: 'extraDelaySeconds', name: '额外延迟上限（秒）', min: 0, max: 3600 },
  { key: 'readIntervalSeconds', name: '详情读取间隔（秒）', min: 1, max: 3600 },
  { key: 'pageIntervalSeconds', name: '翻页 / 切关键词间隔（秒）', min: 1, max: 86400 },
  { key: 'startupSeconds', name: '启动缓冲（秒）', min: 1, max: 3600 },
  { key: 'restEvery', name: '连续尝试多少岗后休息', min: 1, max: 100 },
  { key: 'continuousMinutes', name: '持续处理多久后休息（分钟）', min: 1, max: 240 },
  { key: 'restMinutes', name: '每次休息（分钟）', min: 1, max: 240 },
  { key: 'dailyLimit', name: '本平台每日尝试上限', min: 1, max: 500 },
  { key: 'detailBudget', name: '每日详情读取预算（含预览）', min: 1, max: 2000 },
  { key: 'actionIntervalSeconds', name: '同岗位后续材料间隔（秒）', min: 1, max: 300 },
]
const strings = (value: object) => Object.fromEntries(Object.entries(value).map(([k, v]) => [k, String(v)]))
const remaining = (until: number, now: number) => `${Math.max(0, Math.ceil((until - now) / 1000))} 秒`

export default function DeliveryPacingSettings() {
  const [scope, setScope] = useState<PacingPlatform | 'global'>('boss')
  const [snapshot, setSnapshot] = useState<PacingSnapshot | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [version, setVersion] = useState(0)
  const [error, setError] = useState('')
  const [loadError, setLoadError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [acknowledged, setAcknowledged] = useState(false)
  const [now, setNow] = useState(Date.now())
  const [offset, setOffset] = useState(0)
  const load = useCallback(async () => {
    const response = await fetch(`${API_BASE}/api/delivery/pacing`, { cache: 'no-store', signal: AbortSignal.timeout(10000) })
    const data = await response.json()
    if (!response.ok) throw new Error(data.message || '读取投递保护状态失败')
    setSnapshot(data); setOffset(data.serverTime - Date.now()); return data as PacingSnapshot
  }, [])
  useEffect(() => {
    let live = true
    const refresh = () => load().then(() => { if (live) setLoadError('') }).catch(e => { if (live) setLoadError(e.message) })
    refresh(); const timer = setInterval(refresh, 5000)
    return () => { live = false; clearInterval(timer) }
  }, [load])
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer) }, [])
  // Keep periodic status refreshes from overwriting a form being edited.
  useEffect(() => {
    if (!snapshot || version) return
    const current = scope === 'global' ? snapshot.global : snapshot.platforms[scope]
    setDraft(strings(current.policy)); setVersion(current.version)
  }, [snapshot, scope, version])
  const choose = (value: PacingPlatform | 'global') => {
    setScope(value); setAcknowledged(false); setMessage(''); setError('')
    if (snapshot) {
      const current = value === 'global' ? snapshot.global : snapshot.platforms[value]
      setDraft(strings(current.policy)); setVersion(current.version)
    } else setVersion(0)
  }
  const save = async (event: React.FormEvent) => {
    event.preventDefault(); setBusy(true); setError(''); setMessage('')
    try {
      const policy = Object.fromEntries(Object.entries(draft).map(([key, value]) => {
        if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value))) throw new Error('请填写完整的整数配置')
        return [key, Number(value)]
      }))
      const response = await fetch(`${API_BASE}/api/delivery/pacing/policies/${scope}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ version, policy }) })
      const data = await response.json(); if (!response.ok) throw new Error(data.message || '保存失败')
      setSnapshot(data); setVersion(0); setMessage('已保存。降低每日预算立即生效，提高每日预算次日生效；已经开始的等待不会缩短。')
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败') } finally { setBusy(false) }
  }
  const resume = async () => {
    setBusy(true); setError('')
    try {
      const response = await fetch(`${API_BASE}/api/delivery/pacing/${scope}/resume`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ acknowledged }) })
      const data = await response.json(); if (!response.ok) throw new Error(data.message || '恢复失败')
      setSnapshot(data); setAcknowledged(false); setMessage('保护暂停已解除，请回作战台手动启动任务。待核实岗位仍不会自动重投。')
    } catch (e) { setError(e instanceof Error ? e.message : '恢复失败') } finally { setBusy(false) }
  }
  const current = snapshot && (scope === 'global' ? snapshot.global : snapshot.platforms[scope])
  const platform = snapshot && scope !== 'global' ? snapshot.platforms[scope] : null
  const serverNow = now + offset
  return <Card id="delivery-pacing">
    <CardHeader>
      <CardTitle role="heading" aria-level={2}>四平台投递节奏与保护</CardTitle>
      <CardDescription>限制读取和投递频率，异常时暂停。间隔与额外延迟不能保证平台不限制账号；切换方案、刷新或重启均不清除保护。</CardDescription>
    </CardHeader>
    <CardContent className="space-y-5">
      <div className="flex flex-wrap gap-2" role="group" aria-label="选择节奏配置平台">
        {(['boss', 'zhilian', '51job', 'liepin', 'global'] as const).map(p => <Button key={p} type="button" variant={scope === p ? 'default' : 'outline'} disabled={busy} aria-pressed={scope === p} onClick={() => choose(p)}>{p === 'global' ? '四平台合计' : platformNames[p]}</Button>)}
      </div>
      {(error || loadError) && <p role="alert" className="rounded-xl bg-red-500/10 p-3 text-sm text-red-700 dark:text-red-300">{error || loadError}<Button variant="ghost" onClick={() => { setVersion(0); load().then(() => { setError(''); setLoadError('') }).catch(e => setLoadError(e.message)) }}>重新读取</Button></p>}
      {message && <p role="status" className="rounded-xl bg-emerald-500/10 p-3 text-sm">{message}</p>}
      {!snapshot && !error && <p role="status">正在读取投递保护配置…</p>}
      {snapshot && current && <>
        <div className="rounded-xl border border-white/30 bg-white/5 p-4 text-sm leading-relaxed" aria-live="polite">
          <p>今日已尝试 {current.used} / {platform ? platform.effectiveLimit : current.policy.dailyLimit} 岗；四平台合计 {snapshot.global.used} / {snapshot.global.policy.dailyLimit} 岗。成功与结果待核实均计入。</p>
          {current.pending_limit !== null && <p>提高后的每日预算 {current.pending_limit} 将于 {current.pending_day} 生效。</p>}
          {platform && <>
            <p>今日详情已读 {platform.state.read_day === snapshot.day ? platform.state.read_count : 0} / {platform.policy.detailBudget} 次；预览和未匹配岗位也计入。</p>
            {platform.state.paused ? <p className="font-medium text-amber-800 dark:text-amber-300">已暂停：{platform.state.paused}</p>
              : platform.state.rest_until > serverNow ? <p>中途休息，剩余 {remaining(platform.state.rest_until, serverNow)}</p>
                : Math.max(platform.state.next_delivery, platform.state.start_until) > serverNow ? <p>本平台投递等待，剩余 {remaining(Math.max(platform.state.next_delivery, platform.state.start_until), serverNow)}</p>
                  : <p>当前无本平台间隔等待；实际投递仍需登录、页面、额度及全局资格检查。</p>}
          </>}
        </div>
        <form onSubmit={save} className="space-y-5">
          <div className="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
            {(scope === 'global' ? [{ key: 'minIntervalSeconds', name: '四平台新投递最小间隔（秒）', min: 1, max: 3600 }, { key: 'dailyLimit', name: '四平台每日合计尝试上限', min: 1, max: 2000 }] : fields).map(field => <div className="space-y-2" key={field.key}>
              <Label htmlFor={`pacing-${scope}-${field.key}`}>{field.name}</Label>
              <Input id={`pacing-${scope}-${field.key}`} required type="number" inputMode="numeric" min={field.min} max={field.max} step={1} value={draft[field.key] ?? ''} onChange={e => setDraft({ ...draft, [field.key]: e.target.value })} className="rounded-xl border-white/40 bg-white/5" />
            </div>)}
          </div>
          <p className="text-xs leading-relaxed text-muted-foreground">额外延迟为 0 到上限的附加等待，不缩短最小间隔。连续岗数或处理时长先到者触发休息。原平台额度更低时取更低值。BOSS 原 waitTime 在统一保护启用时由这里接管；智联弹窗关闭等待独立保留。</p>
          <div className="flex flex-wrap gap-3">
            <Button type="submit" disabled={busy || !version}>{busy ? '正在处理…' : '保存投递保护配置'}</Button>
            <Button type="button" variant="outline" disabled={busy} onClick={() => setDraft(strings(scope === 'global' ? { minIntervalSeconds: 30, dailyLimit: 40 } : pacingDefaults))}>填入推荐初值</Button>
          </div>
        </form>
        {platform?.state.paused && <div className="space-y-3 rounded-xl border border-amber-500/30 p-4">
          <p className="text-sm">先停止平台任务，在对应浏览器检查验证、账号状态和待核实投递。解除暂停后仍需手动启动。</p>
          {platform.state.not_before > serverNow && <p className="text-sm">平台最早允许恢复还需 {remaining(platform.state.not_before, serverNow)}；届时也不会自动恢复。</p>}
          <Label className="flex items-start gap-2"><input type="checkbox" checked={acknowledged} onChange={e => setAcknowledged(e.target.checked)} className="mt-1" />我已检查平台页面及待核实投递结果</Label>
          <Button type="button" variant="outline" disabled={busy || !acknowledged || platform.state.not_before > serverNow} onClick={resume}>解除本平台保护暂停</Button>
        </div>}
      </>}
    </CardContent>
  </Card>
}
