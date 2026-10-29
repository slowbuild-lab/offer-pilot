'use client'

import { useEffect, useRef, useState } from 'react'
import { API_BASE } from '@/lib/api'
import { recentScreeningRange, safeBossLink, type RuleCheck } from '@/lib/screening'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import ScreeningEvidence from './ScreeningEvidence'

type Query = { from: string; to: string; result: string }
type Screening = { id: number; task_id: string; job_id: string; profile_version: number; title: string; company: string; link: string; eligible: number; reason: string; checks: RuleCheck[]; evaluated_at: number }
const primaryButton = 'rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4'
const selectStyle = 'h-10 w-full min-w-0 rounded-full border border-white/40 bg-white/5 px-4 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400/40 [&_option]:bg-background'

export default function CandidateScreeningPanel() {
  const [query, setQuery] = useState<Query>({ from: '', to: '', result: '' })
  const [editing, setEditing] = useState<Query>(query)
  const [page, setPage] = useState(1)
  const [ready, setReady] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [items, setItems] = useState<Screening[]>([])
  const [total, setTotal] = useState<number | null>(null)
  const [hasNext, setHasNext] = useState(false)
  const generation = useRef(0)
  useEffect(() => { const initial = recentScreeningRange(); setQuery(initial); setEditing(initial); setReady(true) }, [])
  useEffect(() => {
    if (!ready) return
    const current = ++generation.current
    const controller = new AbortController()
    setBusy(true); setError(''); setItems([]); setTotal(null); setHasNext(false)
    const params = new URLSearchParams({ ...query, page: String(page) })
    const load = async () => {
      try {
        const response = await fetch(`${API_BASE}/api/delivery/screenings?${params}`, { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]) })
        const data = await response.json()
        if (!response.ok) throw new Error(data.message || '筛选记录读取失败')
        if (!Array.isArray(data.items) || !Number.isInteger(data.total)) throw new Error('筛选记录格式异常，请刷新重试')
        if (current !== generation.current || controller.signal.aborted) return
        setItems(data.items); setTotal(data.total); setHasNext(data.hasNext === true)
      } catch (e) {
        if (current === generation.current && !controller.signal.aborted) setError(e instanceof Error ? e.message : '筛选记录读取失败')
      } finally { if (current === generation.current && !controller.signal.aborted) setBusy(false) }
    }
    void load()
    return () => controller.abort()
  }, [query, page, ready, refresh])
  const apply = (next = editing) => {
    if (next.from && next.to && next.from > next.to) { setError('开始日期不能晚于结束日期'); return }
    setBusy(true); setItems([]); setTotal(null); setPage(1); setQuery({ ...next }); setEditing({ ...next })
  }
  return <Card id="boss-screenings" role="region" aria-label="BOSS候选筛选记录" aria-busy={busy} className="scroll-mt-6">
    <CardHeader><CardTitle role="heading" aria-level={2}>BOSS 候选筛选记录</CardTitle><CardDescription>查看新任务中已确认求职方案的判断及原文；同一轮的同一岗位不重复计数。筛选通过不代表已经投递。</CardDescription></CardHeader>
    <CardContent className="space-y-5">
      <form aria-label="BOSS筛选记录查询" onSubmit={e => { e.preventDefault(); apply() }} className="space-y-4">
        <div className="grid grid-cols-1 gap-5 md:grid-cols-3">
          <div className="min-w-0 space-y-2"><Label htmlFor="screening-from">筛选开始日期</Label><Input id="screening-from" type="date" min="1970-01-01" max="9998-12-31" value={editing.from} onChange={e => setEditing(q => ({ ...q, from: e.target.value }))} className="min-w-0" /></div>
          <div className="min-w-0 space-y-2"><Label htmlFor="screening-to">筛选结束日期</Label><Input id="screening-to" type="date" min="1970-01-01" max="9998-12-31" value={editing.to} onChange={e => setEditing(q => ({ ...q, to: e.target.value }))} className="min-w-0" /></div>
          <div className="min-w-0 space-y-2"><Label htmlFor="screening-result">规则评估结果</Label><select id="screening-result" value={editing.result} onChange={e => setEditing(q => ({ ...q, result: e.target.value }))} className={selectStyle}><option value="">全部评估</option><option value="passed">规则通过</option><option value="excluded">规则未通过</option></select></div>
        </div>
        <div className="flex flex-wrap gap-2"><Button disabled={busy} className={primaryButton}>查询筛选记录</Button><Button type="button" disabled={busy} onClick={() => apply({ from: '', to: '', result: editing.result })} variant="outline" className="rounded-full">全部日期</Button><Button type="button" disabled={busy} onClick={() => setRefresh(n => n + 1)} variant="ghost" className="rounded-full">刷新筛选记录</Button></div>
      </form>
      <p className="text-xs text-muted-foreground">日期按北京时间的评估时间筛选，包含结束当天。只读预览在“求职方案”中查看；这里不回填历史岗位，也不包含未使用统一方案的旧任务。</p>
      {error && <p role="alert" className="rounded-xl bg-red-500/5 p-3 text-sm text-red-700 dark:text-red-300">{error}</p>}
      <p role="status" className="text-sm text-muted-foreground">{busy ? '正在读取筛选记录…' : total != null ? `符合条件 ${total} 条筛选记录` : '尚未取得筛选记录'}</p>
      {!busy && !error && total === 0 && <div className="rounded-xl border border-dashed border-primary/20 bg-primary/5 p-6 text-sm text-muted-foreground">当前范围没有筛选记录。确认方案后，新运行的 BOSS 任务会保存实际评估；可先去求职方案进行只读预览。</div>}
      {items.map(item => <article key={item.id} className="space-y-3 rounded-xl border border-border bg-background/40 p-4">
        <div className="flex flex-wrap items-start justify-between gap-2"><h3 className="min-w-0 break-words text-sm font-medium">{item.title || item.job_id} · {item.company || '公司信息缺失'}</h3><span className={`rounded-full px-3 py-1 text-xs ${item.eligible === 1 ? 'bg-primary/10 text-primary' : 'bg-amber-500/10 text-amber-800 dark:text-amber-300'}`}>{item.eligible === 1 ? '可候选' : '规则未通过'}</span></div>
        <p className="text-xs text-muted-foreground">{new Date(item.evaluated_at).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })} · 求职方案 v{item.profile_version}</p>
        <p className="break-words text-sm">{item.reason}</p>
        <ScreeningEvidence checks={item.checks} />
        {safeBossLink(item.link) && <Button asChild size="sm" variant="ghost" className="rounded-full"><a href={safeBossLink(item.link)!} target="_blank" rel="noopener noreferrer">查看原岗位</a></Button>}
      </article>)}
      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4"><Button variant="outline" disabled={busy || !!error || page === 1} onClick={() => { setBusy(true); setPage(n => n - 1) }} className="rounded-full">筛选上一页</Button><span className="text-xs text-muted-foreground">第 {page} 页{total != null && ` / ${Math.max(1, Math.ceil(total / 20))} 页`}</span><Button variant="outline" disabled={busy || !!error || !hasNext || page >= 10000} onClick={() => { setBusy(true); setPage(n => n + 1) }} className="rounded-full">筛选下一页</Button></div>
      <p className="text-xs text-muted-foreground">规则通过后仍需检查停止、紧急禁止、去重与额度。实际外发及结果以投递记录为准；工作内容和公司规则目前仅 BOSS 接入。</p>
    </CardContent>
  </Card>
}
