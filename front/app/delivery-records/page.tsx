'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { API_BASE } from '@/lib/api'

type Summary = { effectiveLimit: number; pendingLimit: number | null; pendingDay: string | null; todayAttempts: number; reservedCount: number; remainingToday: number; unknownCount: number }
type Attempt = { id: string; job_id: string; job_title: string; company: string; job_link: string; state: string; reason: string; initiated_at: number; profile_version: number | null; actions?: { action: string; state: string }[] }
const states: Record<string, string> = { RESERVED: '预留中，尚未提交', SENT: '已发起，等待确认', CONFIRMED: '页面已确认', UNKNOWN: '结果待核实', RELEASED: '提交前取消', CANCELLED: '该动作在点击前取消', NOT_DELIVERED: '本人核实未完成', USER_CONFIRMED: '本人核实已完成' }
const actions: Record<string, string> = { CHAT: '发起沟通', TEXT: '文字消息', IMAGE: '图片简历' }
async function request(path: string, init?: RequestInit) {
  const res = await fetch(`${API_BASE}${path}`, init)
  const data = await res.json()
  if (!res.ok) throw new Error(data.message || '请求失败，请检查服务状态')
  return data
}
function safeJobLink(link: string) {
  try { const u = new URL(link); return u.protocol === 'https:' && (u.hostname === 'www.zhipin.com' || u.hostname.endsWith('.zhaopin.com')) ? u.href : null } catch { return null }
}
export default function DeliveryRecordsPage() {
  const [platform, setPlatform] = useState('boss')
  const [page, setPage] = useState(1)
  const [summary, setSummary] = useState<Summary | null>(null)
  const [items, setItems] = useState<Attempt[]>([])
  const [limit, setLimit] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const load = useCallback(async () => {
    setBusy(true); setError('')
    try {
      const [all, records] = await Promise.all([request('/api/delivery/summary'), request(`/api/delivery/records?platform=${platform}&page=${page}`)])
      setSummary(all[platform]); setLimit(String(all[platform].pendingLimit ?? all[platform].effectiveLimit)); setItems(records.items)
    } catch (e) { setError(e instanceof Error ? e.message : '加载失败') }
    finally { setBusy(false) }
  }, [platform, page])
  useEffect(() => { void load() }, [load])
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
  return <div className="mx-auto min-w-0 max-w-5xl space-y-6 p-4 md:p-8">
    <header><h1 className="text-2xl font-semibold">投递记录与额度</h1><p className="mt-2 text-sm text-muted-foreground">按北京时间统计本工具的岗位尝试。BOSS 沟通、文字和图片分别记录，页面确认不代表 HR 已读。</p></header>
    <nav aria-label="记录导航" className="flex flex-wrap gap-4 text-sm"><Link href="/">返回作战台</Link><Link href="/job-profile">求职方案</Link><Link href="/zhilian">智联待核实处理</Link></nav>
    <div className="flex flex-wrap items-center gap-3"><label htmlFor="record-platform">平台</label><select id="record-platform" value={platform} onChange={e => { setPlatform(e.target.value); setPage(1); setSummary(null); setItems([]); setNotice('') }} className="rounded border bg-background p-2"><option value="boss">BOSS 直聘</option><option value="zhilian">智联招聘</option></select><button disabled={busy} onClick={() => void load()} className="rounded border px-4 py-2 disabled:opacity-50">{busy ? '处理中…' : '刷新'}</button></div>
    {error && <p role="alert" className="rounded border border-red-400 p-3 text-red-600 dark:text-red-300">{error}；当前显示可能不是最新数据。</p>}
    {notice && <p role="status" className="text-sm">{notice}</p>}
    {summary && <section aria-label="今日额度" className="space-y-4 rounded-lg border p-5">
      <div className="flex flex-wrap gap-x-8 gap-y-2"><p>已尝试 <strong>{summary.todayAttempts}</strong></p><p>预留 <strong>{summary.reservedCount}</strong></p><p>剩余 <strong>{summary.remainingToday}</strong></p><p>历史待核实 <strong>{summary.unknownCount}</strong></p></div>
      <p className="text-sm">今日有效上限：{summary.effectiveLimit}{summary.pendingLimit != null && `；${summary.pendingDay} 起调整为 ${summary.pendingLimit}`}</p>
      <div className="flex flex-wrap items-center gap-3"><label htmlFor="daily-limit">平台每日上限</label><input id="daily-limit" inputMode="numeric" type="number" min={1} max={500} value={limit} onChange={e => setLimit(e.target.value)} className="w-28 rounded border bg-background p-2" /><button disabled={busy} onClick={() => void saveLimit()} className="rounded bg-primary px-4 py-2 text-primary-foreground disabled:opacity-50">保存额度</button></div>
      <p className="text-sm text-muted-foreground">手动与自动共用。下调从下一岗位生效，上调次日生效；已经占额岗位的后续已授权动作不重复扣岗位数。本轮 BOSS 暂以 20 个岗位为上限。</p>
    </section>}
    <section aria-label="岗位记录" aria-busy={busy} className="space-y-3">
      {!busy && !error && items.length === 0 && <p className="rounded border p-6 text-muted-foreground">此页暂无记录。旧 BOSS 点击历史不伪造成新口径成功记录。</p>}
      {items.map(item => <article key={item.id} className="space-y-2 rounded-lg border p-4">
        <div className="flex flex-wrap justify-between gap-2"><h2 className="font-medium">{item.job_title || item.job_id} · {item.company || '公司信息缺失'}</h2><span className="text-sm">{states[item.state] || item.state}</span></div>
        <p className="text-sm text-muted-foreground">{new Date(item.initiated_at).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })} · {item.profile_version ? `求职方案 v${item.profile_version}` : '平台原配置'} · {item.reason || '记录中'}</p>
        {item.actions?.map(action => <p key={action.action} className="text-sm">{actions[action.action] || action.action}：{states[action.state] || action.state}</p>)}
        <div className="flex flex-wrap gap-4 text-sm">{safeJobLink(item.job_link) && <a href={safeJobLink(item.job_link)!} target="_blank" rel="noopener noreferrer" className="underline">打开原岗位</a>}{platform === 'boss' && item.state === 'UNKNOWN' && <><button disabled={busy} onClick={() => void resolve(item.id, true)} className="underline disabled:opacity-50">核实已完成</button><button disabled={busy} onClick={() => void resolve(item.id, false)} className="underline disabled:opacity-50">核实未完成</button></>}{item.company && <button disabled={busy} onClick={() => void forbid('company', item.company)} className="underline disabled:opacity-50">禁止该公司</button>}<button disabled={busy} onClick={() => void forbid(platform, item.job_id)} className="underline disabled:opacity-50">禁止该岗位</button></div>
      </article>)}
    </section>
    <div className="flex items-center gap-4"><button disabled={busy || page === 1} onClick={() => setPage(p => p - 1)} className="rounded border px-4 py-2 disabled:opacity-50">上一页</button><span>第 {page} 页</span><button disabled={busy || items.length < 20 || page >= 10000} onClick={() => setPage(p => p + 1)} className="rounded border px-4 py-2 disabled:opacity-50">下一页</button></div>
  </div>
}
