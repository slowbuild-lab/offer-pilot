'use client'

import { useCallback, useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export interface SafetyStatus {
  taskState?: string
  attemptCount?: number
  todayAttempts?: number
  remainingToday?: number
  unknownCount?: number
  statisticsSince?: number | null
}
interface UnknownAttempt {
  id: string
  jobTitle: string
  company: string
  jobLink?: string
  reason: string
}
const states: Record<string, string> = {
  IDLE: '空闲', STARTING: '启动中', RUNNING: '运行中', STOPPING: '停止中', STOPPED: '已停止',
  PAUSED: '已暂停，等待人工处理', COMPLETED: '已完成', FAILED: '执行失败',
  LIMIT_REACHED: '已达配置上限', PLATFORM_LIMIT: '已达平台今日上限',
}
function safeJobLink(raw?: string) {
  try {
    const url = new URL(raw || '')
    return url.protocol === 'https:' && (url.hostname === 'zhaopin.com' || url.hostname.endsWith('.zhaopin.com')) ? url.href : null
  } catch { return null }
}

export default function DeliverySafetyPanel({ status, isRunning, onMessage }: {
  status: SafetyStatus
  isRunning: boolean
  onMessage: (message: string, error: boolean) => void
}) {
  const [items, setItems] = useState<UnknownAttempt[]>([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [resolution, setResolution] = useState<{ item: UnknownAttempt; delivered: boolean } | null>(null)
  const load = useCallback(async () => {
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/attempts/unknown?page=${page}`)
      if (!response.ok) throw new Error('待核实记录加载失败，请重试')
      const data = await response.json()
      setItems(data.items || [])
      setTotal(data.total || 0)
      setError('')
    } catch (e) { setError(e instanceof Error ? e.message : '加载失败，请重试') }
  }, [page])
  useEffect(() => { void load() }, [load, status.unknownCount])

  const resume = async () => {
    setBusy(true)
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/resume`, { method: 'POST' })
      const data = await response.json()
      onMessage(data.message || '继续请求已发送', !response.ok)
    } catch { onMessage('继续请求未确认，请刷新状态后重试', true) }
    finally { setBusy(false) }
  }
  const resolve = async () => {
    if (!resolution) return
    setBusy(true)
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/attempts/${resolution.item.id}/resolve`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ delivered: resolution.delivered }),
      })
      const data = await response.json()
      if (!response.ok) throw new Error(data.message || '核实结果保存失败')
      onMessage(data.message, false)
      setResolution(null)
      await load()
    } catch (e) { onMessage(e instanceof Error ? e.message : '保存失败，请重试', true) }
    finally { setBusy(false) }
  }

  return <Card>
    <CardHeader><CardTitle>投递控制</CardTitle></CardHeader>
    <CardContent className="space-y-4">
      <p role="status">任务状态：{states[status.taskState || 'IDLE'] || '状态待更新'}</p>
      <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
        <span>本次尝试：{status.attemptCount ?? '—'}</span>
        <span>今日尝试：{status.todayAttempts ?? '—'}</span>
        <span>今日剩余：{status.remainingToday ?? '—'}</span>
        <span>待核实：{status.unknownCount ?? total}</span>
      </div>
      <p className="text-sm text-muted-foreground">统计仅覆盖本工具记录的投递尝试，结果未确认仍占次数。
        {status.statisticsSince ? ` 记录始于 ${new Date(status.statisticsSince).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })}（北京时间）。` : ' 首次启动新任务后开始记录。'}
      </p>
      {status.taskState === 'PAUSED' && <div className="space-y-2">
        <p className="text-sm">请先在智联窗口处理验证或登录，并核实下方岗位。继续时使用原任务配置，已确认和待核实岗位会跳过。</p>
        <Button onClick={resume} disabled={busy || isRunning}>{busy ? '正在处理…' : '我已处理，继续投递'}</Button>
      </div>}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-medium">结果待核实的岗位</h3>
        <Button variant="outline" size="sm" onClick={load} disabled={busy}>刷新记录</Button>
      </div>
      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      {!error && items.length === 0 && <p className="text-sm text-muted-foreground">当前页没有待核实记录。</p>}
      {items.length > 0 && <p className="text-sm text-muted-foreground">先打开岗位检查实际投递结果，再登记。任务运行期间请先停止后核实；登记不会自动重投，也不会返还尝试次数。</p>}
      <ul className="space-y-3">
        {items.map(item => {
          const link = safeJobLink(item.jobLink)
          return <li key={item.id} className="rounded-md border p-3 space-y-2">
            <p className="font-medium">{item.jobTitle || '岗位名称待核实'} · {item.company || '公司未记录'}</p>
            <p className="text-sm text-muted-foreground">{item.reason}</p>
            <div className="flex flex-wrap items-center gap-3 text-sm">
              {link && <a className="underline" href={link} target="_blank" rel="noopener noreferrer">查看原岗位</a>}
              <Button variant="outline" size="sm" disabled={busy || isRunning} onClick={() => setResolution({ item, delivered: true })}>核实为已投递</Button>
              <Button variant="outline" size="sm" disabled={busy || isRunning} onClick={() => setResolution({ item, delivered: false })}>核实为未成功</Button>
            </div>
          </li>
        })}
      </ul>
      {total > 50 && <div className="flex items-center gap-3">
        <Button variant="outline" size="sm" disabled={page === 1 || busy} onClick={() => setPage(p => p - 1)}>上一页</Button>
        <span className="text-sm">第 {page} 页，共 {total} 条</span>
        <Button variant="outline" size="sm" disabled={page * 50 >= total || busy} onClick={() => setPage(p => p + 1)}>下一页</Button>
      </div>}
      {resolution && <section role="region" aria-label="确认核实结果" className="rounded-md border border-amber-300 p-4 space-y-3">
        <p>确认将“{resolution.item.jobTitle}”登记为{resolution.delivered ? '已投递' : '未成功'}？请以智联页面的实际记录为准。</p>
        <div className="flex gap-3">
          <Button disabled={busy || isRunning} onClick={resolve}>确认登记</Button>
          <Button variant="outline" disabled={busy} onClick={() => setResolution(null)}>取消</Button>
        </div>
      </section>}
    </CardContent>
  </Card>
}
