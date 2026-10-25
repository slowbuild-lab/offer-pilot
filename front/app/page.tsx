'use client'

import Link from 'next/link'
import { useCallback, useEffect, useRef, useState } from 'react'
import { BiErrorCircle, BiPlay, BiRefresh, BiStop } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import PlatformCard, { type PlatformView } from '@/app/components/PlatformCard'
import PreflightDialog from '@/app/components/PreflightDialog'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'
import { PLATFORM_META, platformName, runPreflight, type PreflightResult } from '@/lib/preflight'

interface Summary {
  parallel: { maxParallel: number; runningCount: number; activeCount: number; activePlatforms: string[] }
  platforms: Record<string, PlatformView>
  today: { zhilianAttempts: number | null; zhilianRemaining: number | null; zhilianDayLimit: number; unknownCount: number; error?: string }
  attention: { count: number; items: AttentionItem[] }
  recent: { id: string; state: string; reason: string; startedAt: number }[]
}

interface AttentionItem {
  platform: string
  kind: string
  ref?: string
  title: string
  message: string
  action: string
}

const RECENT_STATE: Record<string, string> = {
  PAUSED: '暂停', RUNNING: '运行中', COMPLETED: '完成', FAILED: '失败',
  STOPPED: '已停止', PLATFORM_LIMIT: '平台上限', LIMIT_REACHED: '达上限',
}

function formatTime(value: number) {
  try {
    return new Date(value).toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch {
    return '—'
  }
}

export default function WorkbenchPage() {
  const [summary, setSummary] = useState<Summary | null>(null)
  const [offline, setOffline] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<{ text: string; type: 'info' | 'warning' | 'error' } | null>(null)
  const [preflight, setPreflight] = useState<PreflightResult | null>(null)
  const polling = useRef(false)

  const refresh = useCallback(async () => {
    if (polling.current) return
    polling.current = true
    try {
      const response = await fetch(`${API_BASE}/api/workbench/summary`)
      if (!response.ok) throw new Error(`status ${response.status}`)
      setSummary(await response.json())
      setOffline(false)
    } catch {
      setOffline(true)
    } finally {
      polling.current = false
    }
  }, [])

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(refresh, 2000)
    return () => window.clearInterval(timer)
  }, [refresh])

  const call = async (url: string, body?: unknown) => {
    const response = await fetch(`${API_BASE}${url}`, {
      method: 'POST',
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    })
    const data = await response.json().catch(() => ({}))
    return { ok: response.ok, data } as { ok: boolean; data: { success?: boolean; message?: string } }
  }

  const start = async (platform: string) => {
    setBusy(platform)
    try {
      // 预检只是把错误提前；接口不可用时仍然走原有启动流程
      const pre = await runPreflight(platform)
      if (pre && !pre.ready) {
        setPreflight(pre)
        return
      }
      if (pre?.warnings?.length) {
        setMessage({ type: 'warning', text: `${platformName(platform)}：${pre.warnings.join('；')}（不影响启动）` })
      }
      const { data } = await call(PLATFORM_META[platform].start)
      setMessage({ type: data.success ? 'info' : 'error', text: data.message || (data.success ? '已启动' : '启动失败') })
    } finally {
      setBusy(null)
      await refresh()
    }
  }

  const stop = async (platform: string) => {
    setBusy(platform)
    try {
      const { data } = await call(PLATFORM_META[platform].stop)
      setMessage({ type: data.success ? 'warning' : 'error', text: data.message || '停止请求已发送' })
    } finally {
      setBusy(null)
      await refresh()
    }
  }

  const resume = async (platform: string) => {
    setBusy(platform)
    try {
      const { data } = await call('/api/zhilian/resume')
      setMessage({ type: data.success ? 'info' : 'error', text: data.message || '继续请求已发送' })
    } finally {
      setBusy(null)
      await refresh()
    }
  }

  const login = async (platform: string) => {
    setBusy(platform)
    try {
      const { data } = await call(`/api/platforms/${platform}/login`)
      setMessage({ type: data.success ? 'info' : 'error', text: data.message || '正在打开浏览器' })
    } finally {
      setBusy(null)
      await refresh()
    }
  }

  const forceStop = async (platform: string) => {
    const { data } = await call(`/api/platforms/${platform}/force-stop`)
    setMessage({ type: 'warning', text: data.message || '已请求强制结束' })
    await refresh()
  }

  const stopAll = async () => {
    const { data } = await call('/api/platforms/stop-all')
    setMessage({ type: 'warning', text: data.message || '已发送停止请求' })
    await refresh()
  }

  const pre = summary?.parallel
  const today = summary?.today
  const platforms = summary?.platforms || {}
  const order = ['boss', 'zhilian', 'liepin', '51job'].filter((p) => platforms[p])

  const handleAttention = async (item: AttentionItem) => {
    if (item.action === 'resume') return resume(item.platform)
    if (item.action === 'login') return login(item.platform)
    if (item.action === 'open_zhilian_attempts') {
      window.location.href = '/zhilian'
      return
    }
    window.location.href = PLATFORM_META[item.platform]?.page || '/'
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiPlay className="text-2xl" />}
        title="求职作战台"
        subtitle="并行投递总览，每 2 秒刷新"
        iconClass="text-white"
        accentBgClass="bg-indigo-500"
        actions={
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" className="rounded-full px-4" onClick={refresh}>
              <BiRefresh className="mr-1" /> 刷新
            </Button>
            <Button size="sm" variant="outline" className="rounded-full border-amber-500 px-4 text-amber-600" onClick={stopAll}>
              <BiStop className="mr-1" /> 全部停止
            </Button>
          </div>
        }
      />

      {offline && (
        <div role="alert" className="rounded-lg border border-red-300 bg-red-50 p-4 text-sm text-red-900 dark:border-red-900 dark:bg-red-950 dark:text-red-100">
          无法连接后端服务（{API_BASE}），页面显示的是上一次状态。
        </div>
      )}

      {message && (
        <div role="status" aria-live="polite" className={`rounded-lg border p-4 text-sm ${
          message.type === 'error' || message.type === 'warning'
            ? 'border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-100'
            : 'border-blue-200 bg-blue-50 text-blue-900 dark:border-blue-900 dark:bg-blue-950 dark:text-blue-100'
        }`}>
          {message.text}
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Card className="rounded-xl"><CardContent className="p-4">
          <p className="text-sm text-muted-foreground">并行运行</p>
          <p className="text-2xl font-medium">{pre ? `${pre.runningCount} / ${pre.maxParallel}` : '—'}</p>
          {pre && pre.activeCount > pre.runningCount && (
            <p className="text-xs text-muted-foreground">另有 {pre.activeCount - pre.runningCount} 个在启动/停止中</p>
          )}
        </CardContent></Card>
        <Card className="rounded-xl"><CardContent className="p-4">
          <p className="text-sm text-muted-foreground">智联今日尝试</p>
          <p className="text-2xl font-medium">{today?.zhilianAttempts ?? '—'}</p>
          <p className="text-xs text-muted-foreground">仅统计本工具记录的智联尝试</p>
        </CardContent></Card>
        <Card className="rounded-xl"><CardContent className="p-4">
          <p className="text-sm text-muted-foreground">结果待核实</p>
          <p className={`text-2xl font-medium ${(today?.unknownCount || 0) > 0 ? 'text-amber-600 dark:text-amber-400' : ''}`}>
            {today?.unknownCount ?? '—'}
          </p>
          <p className="text-xs text-muted-foreground">未确认的投递不当作未投递</p>
        </CardContent></Card>
        <Card className="rounded-xl"><CardContent className="p-4">
          <p className="text-sm text-muted-foreground">需要处理</p>
          <p className={`text-2xl font-medium ${(summary?.attention.count || 0) > 0 ? 'text-red-600 dark:text-red-400' : ''}`}>
            {summary?.attention.count ?? '—'}
          </p>
          <p className="text-xs text-muted-foreground">见下方清单</p>
        </CardContent></Card>
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        {order.map((platform) => (
          <PlatformCard
            key={platform}
            view={platforms[platform]}
            busy={busy}
            onStart={start}
            onStop={stop}
            onResume={resume}
            onLogin={login}
            onForceStop={forceStop}
          />
        ))}
      </div>

      <Card className="rounded-xl">
        <CardHeader className="pb-2"><CardTitle className="text-base">需要处理</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {!summary && <p className="text-sm text-muted-foreground">加载中…</p>}
          {summary && summary.attention.items.length === 0 && (
            <p className="text-sm text-muted-foreground">暂无需要处理的事项。验证码、登录失效、暂停和待核实记录会出现在这里。</p>
          )}
          {summary?.attention.items.map((item) => (
            <div key={`${item.platform}-${item.kind}-${item.ref || ''}`} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3">
              <div className="min-w-0">
                <p className="text-sm font-medium">
                  <span className="mr-2 rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">{platformName(item.platform)}</span>
                  {item.title}
                </p>
                <p className="mt-1 truncate text-xs text-muted-foreground" title={item.message}>{item.message}</p>
              </div>
              <Button size="sm" variant="outline" className="rounded-full px-3" onClick={() => handleAttention(item)}>
                {item.action === 'resume' ? '去继续' : item.action === 'login' ? '去登录' : '去处理'}
              </Button>
            </div>
          ))}
        </CardContent>
      </Card>

      <Card className="rounded-xl">
        <CardHeader className="pb-2"><CardTitle className="text-base">最近任务（智联）</CardTitle></CardHeader>
        <CardContent>
          {(!summary || summary.recent.length === 0) && (
            <p className="text-sm text-muted-foreground">
              {summary ? '还没有智联任务记录。BOSS 暂未保存任务历史，本次不伪造跨平台记录。' : '加载中…'}
            </p>
          )}
          <ul className="space-y-2">
            {summary?.recent.map((task) => (
              <li key={task.id} className="flex items-center justify-between gap-3 text-sm">
                <span className="text-muted-foreground">{formatTime(task.startedAt)}</span>
                <span className="rounded bg-muted px-2 py-0.5 text-xs">{RECENT_STATE[task.state] || task.state}</span>
                <span className="min-w-0 flex-1 truncate text-right text-muted-foreground" title={task.reason || ''}>
                  {task.reason || '—'}
                </span>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>

      {summary && summary.attention.count > 0 && (
        <p className="flex items-center gap-2 text-xs text-muted-foreground">
          <BiErrorCircle /> 待核实记录不会自动重投，也不会返还已使用的尝试次数。
        </p>
      )}

      <p className="text-xs text-muted-foreground">
        入口保持兼容：<Link className="underline" href="/env-config">环境配置</Link> · <Link className="underline" href="/ai-config">AI配置</Link>
      </p>

      {preflight && (
        <PreflightDialog
          result={preflight}
          onClose={() => setPreflight(null)}
          onLogin={() => {
            const platform = preflight.platform
            setPreflight(null)
            void login(platform)
          }}
        />
      )}
    </div>
  )
}
