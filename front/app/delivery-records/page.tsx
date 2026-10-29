'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { API_BASE } from '@/lib/api'
import { BiTask, BiRefresh, BiSave, BiFilterAlt, BiSearch, BiBarChartAlt2, BiChevronLeft, BiChevronRight, BiLinkExternal, BiInfoCircle } from 'react-icons/bi'
import PageHeader from '@/app/components/PageHeader'
import CandidateScreeningPanel from '@/app/components/CandidateScreeningPanel'
import PacingAttemptsPanel from '@/app/components/PacingAttemptsPanel'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const primaryButton = 'rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300'
const selectStyle = 'h-10 w-full min-w-0 rounded-full border border-white/40 bg-white/5 px-4 py-2 text-sm shadow-[inset_0_1px_0_rgba(255,255,255,.25)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400/40 disabled:opacity-50 [&_option]:bg-background'

type Summary = { effectiveLimit: number; pendingLimit: number | null; pendingDay: string | null; todayAttempts: number; reservedCount: number; remainingToday: number; unknownCount: number }
type Attempt = { id: string; job_id: string; job_title: string; company: string; job_link: string; state: string; reason: string; initiated_at: number; profile_version: number | null; actions?: { action: string; state: string }[] }
const states: Record<string, string> = { RESERVED: '预留中，尚未提交', SENT: '已发起，等待确认', CONFIRMED: '页面已确认', UNKNOWN: '结果待核实', RELEASED: '提交前取消', CANCELLED: '该动作在点击前取消', NOT_DELIVERED: '本人核实未完成', USER_CONFIRMED: '本人核实已完成' }
const actions: Record<string, string> = { CHAT: '发起沟通', TEXT: '文字消息', IMAGE: '图片简历' }
async function request(path: string, init?: RequestInit) {
  const res = await fetch(`${API_BASE}${path}`, { ...init, signal: init?.signal ? AbortSignal.any([init.signal, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000) })
  const data = await res.json()
  if (!res.ok) throw new Error(data.message || '请求失败，请检查服务状态')
  return data
}
function safeJobLink(link: string) {
  try { const u = new URL(link); return u.protocol === 'https:' && (u.hostname === 'www.zhipin.com' || u.hostname.endsWith('.zhaopin.com')) ? u.href : null } catch { return null }
}
function recentRange() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const part = (name: string) => parts.find(p => p.type === name)!.value
  const to = `${part('year')}-${part('month')}-${part('day')}`
  const start = new Date(`${to}T00:00:00Z`); start.setUTCDate(start.getUTCDate() - 6)
  return { from: start.toISOString().slice(0, 10), to, state: '' }
}
export default function DeliveryRecordsPage() {
  const [platform, setPlatform] = useState('boss')
  const [page, setPage] = useState(1)
  const [filters, setFilters] = useState({ from: '', to: '', state: '' })
  const [editing, setEditing] = useState({ from: '', to: '', state: '' })
  const [ready, setReady] = useState(false)
  const [total, setTotal] = useState<number | null>(null)
  const [hasNext, setHasNext] = useState(false)
  const generation = useRef(0)
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const initial = params.get('range') === 'all' ? { from: '', to: '', state: '' } : recentRange()
    if (params.get('state') === 'UNKNOWN') initial.state = 'UNKNOWN'
    if (params.get('platform') === 'zhilian') setPlatform('zhilian')
    setFilters(initial); setEditing(initial); setReady(true)
  }, [])
  const [summary, setSummary] = useState<Summary | null>(null)
  const [items, setItems] = useState<Attempt[]>([])
  const [limit, setLimit] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const load = useCallback(async (signal?: AbortSignal) => {
    if (!ready) return
    const current = ++generation.current
    setBusy(true); setError('')
    try {
      const params = new URLSearchParams({ platform, page: String(page), ...filters })
      const [all, records] = await Promise.all([request('/api/delivery/summary', { signal }), request(`/api/delivery/records?${params}`, { signal })])
      if (current !== generation.current || signal?.aborted) return
      setSummary(all[platform]); setLimit(String(all[platform].pendingLimit ?? all[platform].effectiveLimit)); setItems(records.items)
      setTotal(records.total); setHasNext(records.hasNext)
    } catch (e) {
      if (current === generation.current && !signal?.aborted) setError(e instanceof Error ? e.message : '加载失败')
    } finally { if (current === generation.current && !signal?.aborted) setBusy(false) }
  }, [platform, page, filters, ready])
  useEffect(() => { const controller = new AbortController(); void load(controller.signal); return () => controller.abort() }, [load])
  const applyFilters = (value = editing) => {
    if (value.from && value.to && value.from > value.to) { setError('开始日期不能晚于结束日期'); return }
    setPage(1); setFilters({ ...value }); setEditing({ ...value }); setItems([]); setTotal(null); setNotice('')
  }
  const saveLimit = async () => {
    if (!/^\d+$/.test(limit) || Number(limit) < 1 || Number(limit) > 500) { setError('请输入 1 到 500 的整数'); return }
    setBusy(true); setError('')
    try {
      await request(`/api/delivery/limits/${platform}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ limit: Number(limit) }) })
      setNotice('已保存：下调立即限制新增岗位，上调次个北京时间自然日生效。'); await load()
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败') } finally { setBusy(false) }
  }
  const resolve = async (id: string, completed: boolean) => {
    if (!window.confirm(`确认登记为“${completed ? '已完成' : '未完成'}”？尝试次数保留，不会自动重投。`)) return
    setBusy(true); setError('')
    try {
      await request(`/api/delivery/boss/records/${encodeURIComponent(id)}/resolve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ completed }) })
      setNotice('核实结果已保存。'); await load()
    } catch (e) { setError(e instanceof Error ? e.message : '核实失败') } finally { setBusy(false) }
  }
  const forbid = async (kind: string, value: string) => {
    if (!window.confirm(`立即禁止${kind === 'company' ? '公司（精确名称）' : '该平台岗位'}：“${value}”？下一次外发前生效，已发出内容不能撤回。`)) return
    setBusy(true); setError('')
    try {
      await request('/api/job-profile/forbidden', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind, value }) })
      setNotice('已加入紧急禁止；解除需要后续重新确认方案，本页不提供直接取消。')
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败') } finally { setBusy(false) }
  }
  return <div className="space-y-6">
    <PageHeader icon={<BiTask className="text-2xl" />} title="投递记录与额度" subtitle="管理平台每日额度，查看岗位尝试与待核实结果"
      actions={<Button disabled={busy} onClick={() => void load()} size="sm" className={primaryButton}><BiRefresh className={busy ? 'mr-1 animate-spin' : 'mr-1'} />{busy ? '处理中…' : '刷新'}</Button>} />
    <nav aria-label="记录导航" className="flex flex-wrap gap-2">{[["/", "返回作战台"], ["/job-profile", "求职方案"], ["/zhilian", "智联待核实处理"]].map(([href, title]) => <Button key={href} asChild size="sm" variant="ghost" className="rounded-full text-muted-foreground"><Link href={href}>{title}</Link></Button>)}{platform === 'boss' && <Button asChild size="sm" variant="ghost" className="rounded-full text-muted-foreground"><a href="#boss-screenings">BOSS 筛选原因</a></Button>}</nav>
    {error && <Card role="alert" className="border-red-500/20 bg-red-500/5"><CardContent className="pt-6 text-sm text-red-600 dark:text-red-300">{error}；当前显示可能不是最新数据。</CardContent></Card>}
    {notice && <Card role="status" className="border-blue-500/20 bg-blue-500/5"><CardContent className="pt-6 text-sm">{notice}</CardContent></Card>}
    <Card aria-label="今日额度">
      <CardHeader><CardTitle className="flex items-center gap-2"><BiBarChartAlt2 className="text-primary" />今日额度</CardTitle><CardDescription>按北京时间统计本工具的岗位尝试，手动与自动入口共享每日余额</CardDescription></CardHeader>
      <CardContent className="space-y-6">
        <div className="grid grid-cols-1 items-end gap-6 md:grid-cols-2">
          <div className="space-y-2"><Label htmlFor="record-platform">平台</Label><select id="record-platform" disabled={busy} value={platform} onChange={e => { setPlatform(e.target.value); setPage(1); setSummary(null); setItems([]); setTotal(null); setNotice(''); if (filters.state === 'USER_CONFIRMED') { setFilters(f => ({ ...f, state: '' })); setEditing(f => ({ ...f, state: '' })) } }} className={selectStyle}><option value="boss">BOSS 直聘</option><option value="zhilian">智联招聘</option></select></div>
          {summary && <div className="rounded-xl border border-primary/15 bg-primary/5 px-4 py-3 text-sm"><p>今日有效上限：<strong className="font-semibold text-primary">{summary.effectiveLimit}</strong></p>{summary.pendingLimit != null && <p className="mt-1 text-xs text-muted-foreground">{summary.pendingDay} 起调整为 {summary.pendingLimit}</p>}</div>}
        </div>
        {summary ? <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">{[['已尝试', summary.todayAttempts], ['预留', summary.reservedCount], ['剩余', summary.remainingToday], ['历史待核实', summary.unknownCount]].map(([title, value]) => <div key={title} className="rounded-xl border border-white/20 bg-white/5 p-4"><p className="text-xs text-muted-foreground">{title}</p><p className={`mt-2 text-2xl font-semibold ${title === '历史待核实' && Number(value) > 0 ? 'text-amber-600 dark:text-amber-300' : 'text-foreground'}`}>{value}</p></div>)}</div>
          <div className="flex flex-wrap items-end gap-3"><div className="w-full max-w-xs space-y-2"><Label htmlFor="daily-limit">平台每日上限</Label><Input id="daily-limit" inputMode="numeric" type="number" min={1} max={500} value={limit} onChange={e => setLimit(e.target.value)} /></div><Button disabled={busy} onClick={() => void saveLimit()} className={primaryButton}><BiSave />保存额度</Button></div>
          <p className="text-xs leading-relaxed text-muted-foreground">下调从下一岗位生效，上调次日生效；已经占额岗位的后续已授权动作不重复扣岗位数。本轮 BOSS 暂以 20 个岗位为上限。</p>
        </> : <p className="text-sm text-muted-foreground">{busy ? '正在读取平台额度…' : '尚未取得平台额度'}</p>}
      </CardContent>
    </Card>
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2"><BiFilterAlt className="text-primary" />记录筛选</CardTitle><CardDescription>按平台、日期和岗位结果查找，默认显示最近 7 天</CardDescription></CardHeader>
      <CardContent>
        <form aria-label="记录筛选" onSubmit={e => { e.preventDefault(); applyFilters() }} className="space-y-5">
          <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
            <div className="min-w-0 space-y-2"><Label htmlFor="records-from">开始日期</Label><Input id="records-from" type="date" min="1970-01-01" max="9998-12-31" value={editing.from} onChange={e => setEditing(f => ({ ...f, from: e.target.value }))} className="min-w-0" /></div>
            <div className="min-w-0 space-y-2"><Label htmlFor="records-to">结束日期</Label><Input id="records-to" type="date" min="1970-01-01" max="9998-12-31" value={editing.to} onChange={e => setEditing(f => ({ ...f, to: e.target.value }))} className="min-w-0" /></div>
            <div className="min-w-0 space-y-2"><Label htmlFor="records-state">岗位结果</Label><select id="records-state" value={editing.state} onChange={e => setEditing(f => ({ ...f, state: e.target.value }))} className={selectStyle}><option value="">全部结果</option>{Object.entries(states).filter(([key]) => key !== 'CANCELLED' && (platform === 'boss' || key !== 'USER_CONFIRMED')).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></div>
          </div>
          <div className="flex flex-wrap gap-3"><Button disabled={busy} className={primaryButton}><BiSearch />查询</Button><Button type="button" disabled={busy} onClick={() => applyFilters(recentRange())} variant="outline" className="rounded-full px-4">最近 7 天</Button><Button type="button" disabled={busy} onClick={() => applyFilters({ from: '', to: '', state: editing.state })} variant="outline" className="rounded-full px-4">不限日期</Button></div>
          <p className="text-xs text-muted-foreground">日期按北京时间的岗位尝试发起时间筛选，包含结束当天。今日额度不随筛选改变。</p>
        </form>
      </CardContent>
    </Card>
    <Card aria-label="岗位记录" aria-busy={busy}>
      <CardHeader><CardTitle className="flex items-center gap-2"><BiTask className="text-primary" />岗位记录</CardTitle><CardDescription role="status">{busy ? '正在查询…' : total != null ? `符合条件 ${total} 条` : '尚未取得查询结果'}</CardDescription></CardHeader>
      <CardContent className="space-y-4">
        {!busy && !error && items.length === 0 && <div className="rounded-xl border border-dashed border-primary/20 bg-primary/5 px-5 py-10 text-center"><BiSearch className="mx-auto mb-3 h-8 w-8 text-primary/60" /><p className="text-sm text-muted-foreground">当前筛选范围内没有记录，可调整日期或结果。</p><p className="mt-2 text-xs text-muted-foreground">旧 BOSS 点击历史不会补成新口径记录。</p></div>}
        {items.map(item => <article key={item.id} className="space-y-3 rounded-xl border border-white/20 bg-white/5 p-4 md:p-5">
          <div className="flex flex-wrap items-start justify-between gap-3"><h2 className="min-w-0 break-words text-sm font-medium">{item.job_title || item.job_id} · {item.company || '公司信息缺失'}</h2><span className={`rounded-full px-3 py-1 text-xs ${item.state === 'UNKNOWN' ? 'bg-amber-500/10 text-amber-700 dark:text-amber-300' : 'bg-primary/10 text-primary'}`}>{states[item.state] || item.state}</span></div>
          <p className="text-xs leading-relaxed text-muted-foreground">{new Date(item.initiated_at).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })} · {item.profile_version ? `求职方案 v${item.profile_version}` : '平台原配置'} · {item.reason || '记录中'}</p>
          {item.actions && item.actions.length > 0 && <div className="flex flex-wrap gap-2">{item.actions.map(action => <p key={action.action} className="rounded-lg bg-primary/5 px-3 py-2 text-xs">{actions[action.action] || action.action}：{states[action.state] || action.state}</p>)}</div>}
          <div className="flex flex-wrap gap-2">{safeJobLink(item.job_link) && <Button asChild size="sm" variant="ghost" className="rounded-full"><a href={safeJobLink(item.job_link)!} target="_blank" rel="noopener noreferrer"><BiLinkExternal />打开原岗位</a></Button>}{platform === 'boss' && item.state === 'UNKNOWN' && <><Button size="sm" variant="outline" disabled={busy} onClick={() => void resolve(item.id, true)} className="rounded-full">核实已完成</Button><Button size="sm" variant="outline" disabled={busy} onClick={() => void resolve(item.id, false)} className="rounded-full">核实未完成</Button></>}{item.company && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void forbid('company', item.company)} className="rounded-full text-red-600 dark:text-red-300">禁止该公司</Button>}<Button size="sm" variant="ghost" disabled={busy} onClick={() => void forbid(platform, item.job_id)} className="rounded-full text-red-600 dark:text-red-300">禁止该岗位</Button></div>
        </article>)}
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-white/15 pt-4"><Button variant="outline" disabled={busy || Boolean(error) || page === 1} onClick={() => { setBusy(true); setItems([]); setPage(p => p - 1) }} className="rounded-full px-4"><BiChevronLeft />上一页</Button><span className="text-xs text-muted-foreground">第 {page} 页{total != null && ` / ${Math.max(1, Math.ceil(total / 20))} 页`}</span><Button variant="outline" disabled={busy || Boolean(error) || !hasNext || page >= 10000} onClick={() => { setBusy(true); setItems([]); setPage(p => p + 1) }} className="rounded-full px-4">下一页<BiChevronRight /></Button></div>
      </CardContent>
    </Card>
    {platform === 'boss' && <CandidateScreeningPanel />}
    <PacingAttemptsPanel />
    <Card className="border-primary/20 bg-primary/5"><CardContent className="flex items-start gap-3 pt-6"><BiInfoCircle className="mt-0.5 h-5 w-5 shrink-0 text-primary" /><p className="text-xs leading-relaxed text-muted-foreground">BOSS 沟通、文字和图片分别记录，页面确认不代表 HR 已读。未确认的结果保留次数，不会自动重投。</p></CardContent></Card>
  </div>
}
