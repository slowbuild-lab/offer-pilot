'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { API_BASE } from '@/lib/api'
import { platformNames, type PacingPlatform } from '@/lib/pacing'
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'

type Attempt = { id: string; platform: PacingPlatform; job_id: string; title: string; link: string; state: string; started_at: number; reason: string }
type Results = { items: Attempt[]; total: number; hasNext: boolean }
const labels: Record<string, string> = { RESERVED: '已获准，尚未提交', SENT: '已发起，等待确认', CONFIRMED: '页面已确认', UNKNOWN: '结果待核实', CANCELLED: '提交前取消' }
function safeLink(link: string) {
  try { const u = new URL(link); return u.protocol === 'https:' && ['zhipin.com', 'zhaopin.com', '51job.com', 'liepin.com'].some(d => u.hostname === d || u.hostname.endsWith(`.${d}`)) ? u.href : null } catch { return null }
}
export default function PacingAttemptsPanel() {
  const [platform, setPlatform] = useState('')
  const [page, setPage] = useState(1)
  const [refresh, setRefresh] = useState(0)
  const [result, setResult] = useState<Results | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(true)
  useEffect(() => {
    const abort = new AbortController()
    const params = new URLSearchParams({ page: String(page) }); if (platform) params.set('platform', platform)
    fetch(`${API_BASE}/api/delivery/pacing/records?${params}`, { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(10000)]), cache: 'no-store' })
      .then(async response => { const data = await response.json(); if (!response.ok) throw new Error(data.message || '外发保护记录读取失败'); if (!abort.signal.aborted) setResult(data) })
      .catch(e => { if (!abort.signal.aborted) setError(e.message || '外发保护记录读取失败') })
      .finally(() => { if (!abort.signal.aborted) setBusy(false) })
    return () => abort.abort()
  }, [platform, page, refresh])
  const prepare = () => { setBusy(true); setError(''); setResult(null) }
  return <Card>
    <CardHeader><CardTitle role="heading" aria-level={2}>四平台外发保护记录</CardTitle><CardDescription>记录新保护策略接入后的外发尝试，含 BOSS、智联、前程无忧和猎聘。与上方原账本可能对应同一次投递，请勿将两处数量相加。</CardDescription></CardHeader>
    <CardContent className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-0 space-y-2"><Label htmlFor="pacing-record-platform">外发保护记录平台</Label><select id="pacing-record-platform" value={platform} disabled={busy} onChange={e => { prepare(); setPlatform(e.target.value); setPage(1) }} className="h-10 max-w-full rounded-xl border border-white/40 bg-white/5 px-4 text-sm [&_option]:bg-background"><option value="">四个平台</option>{Object.entries(platformNames).map(([key, name]) => <option key={key} value={key}>{name}</option>)}</select></div>
        <Button variant="outline" disabled={busy} onClick={() => { prepare(); setRefresh(v => v + 1) }}>刷新保护记录</Button>
        <Button asChild variant="ghost"><Link href="/env-config#delivery-pacing">查看节奏设置 / 处理暂停</Link></Button>
      </div>
      {busy && <p role="status" className="text-sm">正在读取外发保护记录…</p>}
      {error && <p role="alert" className="rounded-xl bg-red-500/10 p-3 text-sm text-red-700 dark:text-red-300">{error}，请检查服务后刷新。</p>}
      {result && <p className="text-sm text-muted-foreground">共 {result.total} 条保护记录。提交前取消不占额度；待核实岗位不会自动重投。</p>}
      {result?.items.length === 0 && <p className="rounded-xl bg-white/5 p-4 text-sm text-muted-foreground">暂无外发保护记录。只读预览与间隔等待不会生成已投递记录。</p>}
      {result?.items.map(item => <article key={item.id} className="space-y-2 rounded-xl border border-white/25 bg-white/5 p-4 text-sm">
        <div className="flex flex-wrap justify-between gap-2"><p className="min-w-0 break-words font-medium">{item.title || item.job_id}</p><span className={item.state === 'UNKNOWN' ? 'text-amber-800 dark:text-amber-300' : 'text-muted-foreground'}>{labels[item.state] || item.state}</span></div>
        <p className="break-words text-xs text-muted-foreground">{platformNames[item.platform]} · 岗位 {item.job_id} · {new Date(item.started_at).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })}</p>
        {item.reason && <p className="break-words">{item.reason}</p>}
        {safeLink(item.link) && <a href={safeLink(item.link)!} target="_blank" rel="noopener noreferrer" className="inline-block text-primary underline underline-offset-4">打开对应岗位</a>}
      </article>)}
      <div className="flex flex-wrap items-center justify-between gap-3"><Button variant="outline" disabled={busy || !!error || page === 1} onClick={() => { prepare(); setPage(v => v - 1) }}>保护记录上一页</Button><span className="text-xs text-muted-foreground">第 {page} 页</span><Button variant="outline" disabled={busy || !!error || !result?.hasNext || page >= 10000} onClick={() => { prepare(); setPage(v => v + 1) }}>保护记录下一页</Button></div>
    </CardContent>
  </Card>
}
