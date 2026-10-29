'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { BiErrorCircle, BiPlay, BiRefresh, BiStop } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import PlatformCard, { type PlatformView } from '@/app/components/PlatformCard'
import PreflightDialog from '@/app/components/PreflightDialog'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'
import { PLATFORM_META, platformName, runPreflight, type PreflightResult } from '@/lib/preflight'

interface DeliverySummary { todayAttempts?: number; reservedCount?: number; remainingToday?: number; effectiveLimit?: number; unknownCount?: number; error?: string }

interface Summary {
  parallel: { maxParallel: number; runningCount: number; activeCount: number; activePlatforms: string[] }
  platforms: Record<string, PlatformView>
  today: { zhilianAttempts: number | null; zhilianRemaining: number | null; zhilianDayLimit: number; unknownCount: number | null; error?: string }
  attention: { count: number; items: AttentionItem[] }
  delivery: Record<string, DeliverySummary>
  recent: { id: string; platform: string; state: string; reason: string; startedAt: number }[]
  recentError?: string
  timestamp: number
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
    return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch {
    return '—'
  }
}

export default function WorkbenchPage() {
  const router = useRouter()
  const [summary, setSummary] = useState<Summary | null>(null)
  const [offline, setOffline] = useState(false)
  const [busy, setBusy] = useState<Record<string, boolean>>({})
  const anyBusy = Object.values(busy).some(Boolean)
  const [message, setMessage] = useState<{ text: string; type: 'info' | 'warning' | 'error' } | null>(null)
  const [preflight, setPreflight] = useState<PreflightResult | null>(null)
  const polling = useRef(false)

  const refresh = useCallback(async () => {
    if (polling.current) return
    polling.current = true
    try {
      const response = await fetch(`${API_BASE}/api/workbench/summary`, { signal: AbortSignal.timeout(8000), cache: 'no-store' })
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
    try {
      const response = await fetch(`${API_BASE}${url}`, {
        method: 'POST', signal: AbortSignal.timeout(15000),
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      })
      const data = await response.json().catch(() => ({}))
      return { ok: response.ok, data: { ...data, success: response.ok && data.success === true } }
    } catch {
      return { ok: false, data: { success: false, message: '请求未获确认，请刷新查看实际状态，勿连续重复点击；开始或停止请求可能已被服务接收。' } }
    }
  }

  const start = async (platform: string) => {
    setBusy(current => ({ ...current, [platform]: true }))
    try {
      // 预检失败时不继续启动，避免状态不明时外发。
      const pre = await runPreflight(platform)
      if (!pre) {
        setMessage({ type: 'error', text: '启动预检未完成，请检查本机服务后重试；没有发起启动请求。' })
        return
      }
      if (!pre.ready) {
        setPreflight(pre)
        return
      }
      if (pre?.warnings?.length) {
        setMessage({ type: 'warning', text: `${platformName(platform)}：${pre.warnings.join('；')}（不影响启动）` })
      }
      const { data } = await call(PLATFORM_META[platform].start)
      setMessage({ type: data.success ? 'info' : 'error', text: data.message || (data.success ? '已启动' : '启动失败') })
    } finally {
      setBusy(current => ({ ...current, [platform]: false }))
      await refresh()
    }
  }

  const stop = async (platform: string) => {
    setBusy(current => ({ ...current, [platform]: true }))
    try {
      const { data } = await call(PLATFORM_META[platform].stop)
      setMessage({ type: data.success ? 'warning' : 'error', text: data.message || (data.success ? '停止请求已发送' : '停止请求未获确认，请刷新核对任务状态') })
    } finally {
      setBusy(current => ({ ...current, [platform]: false }))
      await refresh()
    }
  }

  const resume = async (platform: string) => {
    setBusy(current => ({ ...current, [platform]: true }))
    try {
      const { data } = await call('/api/zhilian/resume')
      setMessage({ type: data.success ? 'info' : 'error', text: data.message || (data.success ? '继续请求已发送' : '继续请求失败，请检查服务状态') })
    } finally {
      setBusy(current => ({ ...current, [platform]: false }))
      await refresh()
    }
  }

  const login = async (platform: string) => {
    setBusy(current => ({ ...current, [platform]: true }))
    try {
      const { data } = await call(`/api/platforms/${platform}/login`)
      setMessage({ type: data.success ? 'info' : 'error', text: data.message || (data.success ? '正在打开浏览器' : '打开浏览器失败，请检查服务状态') })
    } finally {
      setBusy(current => ({ ...current, [platform]: false }))
      await refresh()
    }
  }

  const forceStop = async (platform: string) => {
    if (!window.confirm(`强制关闭${platformName(platform)}专用浏览器？已发出的结果仍需核实，其他平台不受影响。`)) return
    setBusy(current => ({ ...current, [platform]: true }))
    try {
      const { data } = await call(`/api/platforms/${platform}/force-stop`)
      setMessage({ type: data.success ? 'warning' : 'error', text: data.message || '强制结束请求未获确认' })
    } finally { setBusy(current => ({ ...current, [platform]: false })); await refresh() }
  }

  const stopAll = async () => {
    if (!window.confirm('停止全部正在运行的任务？已发送的消息和申请不能撤回，未确认结果仍会保留。')) return
    setBusy(current => ({ ...current, all: true }))
    try {
      const { data } = await call('/api/platforms/stop-all')
      setMessage({ type: data.success ? 'warning' : 'error', text: data.message || '全部停止请求未获确认' })
    } finally { setBusy(current => ({ ...current, all: false })); await refresh() }
  }

  const pre = summary?.parallel
  const today = summary?.today
  const platforms = summary?.platforms || {}
  const order = ['boss', 'zhilian', 'liepin', '51job'].filter((p) => platforms[p])

  const handleAttention = async (item: AttentionItem) => {
    if (item.action === 'refresh') return refresh()
    if (item.action === 'open_delivery_records') {
      router.push(`/delivery-records?platform=${encodeURIComponent(item.platform)}&state=UNKNOWN&range=all`)
      return
    }
    if (item.action === 'resume') return resume(item.platform)
    if (item.action === 'login') return login(item.platform)
    if (item.action === 'open_zhilian_attempts') {
      router.push('/zhilian')
      return
    }
    router.push(PLATFORM_META[item.platform]?.page || '/')
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiPlay className="text-2xl" />}
        title="求职领航"
        subtitle="并行投递总览，每 2 秒刷新"
        iconClass="text-white"
        accentBgClass="bg-indigo-500"
        actions={
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" className="rounded-full px-4" onClick={refresh}>
              <BiRefresh className="mr-1" /> 刷新
            </Button>
            <Button size="sm" variant="outline" className="rounded-full border-amber-500 px-4 text-amber-600" disabled={offline || !summary || busy.all || !pre?.activeCount} onClick={stopAll}>
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

      <p className="text-xs text-muted-foreground">{summary ? `最近更新：${formatTime(summary.timestamp)}（北京时间）` : offline ? '尚未取得服务状态' : '正在读取状态…'}</p>

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
          <p className="text-sm text-muted-foreground">今日岗位尝试</p>
          <p className="text-lg font-medium">BOSS {summary?.delivery.boss?.todayAttempts ?? '—'} · 智联 {summary?.delivery.zhilian?.todayAttempts ?? '—'}</p>
          <p className="text-xs text-muted-foreground">预留单列；仅统计本工具记录</p>
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
            busy={busy.all ? 'all' : busy[platform] ? platform : null}
            unavailable={offline || !summary}
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
                  <span className="mr-2 rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">{item.platform === 'system' ? '系统' : platformName(item.platform)}</span>
                  {item.title}
                </p>
                <p className="mt-1 truncate text-xs text-muted-foreground" title={item.message}>{item.message}</p>
              </div>
              <Button size="sm" variant="outline" className="rounded-full px-3" disabled={offline || anyBusy} onClick={() => handleAttention(item)}>
                {item.action === 'resume' ? '去继续' : item.action === 'login' ? '去登录' : '去处理'}
              </Button>
            </div>
          ))}
        </CardContent>
      </Card>

      <Card className="rounded-xl">
        <CardHeader className="pb-2"><CardTitle className="text-base">最近任务（BOSS / 智联）</CardTitle></CardHeader>
        <CardContent>
          {summary?.recentError && <p role="alert" className="text-sm text-red-600">{summary.recentError}</p>}
          {(!summary || (!summary.recentError && summary.recent.length === 0)) && (
            <p className="text-sm text-muted-foreground">
              {summary ? '还没有本工具记录的 BOSS 或智联任务。' : '加载中…'}
            </p>
          )}
          <ul className="space-y-2">
            {summary?.recent.map((task) => (
              <li key={`${task.platform}-${task.id}`} className="flex flex-wrap items-center justify-between gap-3 text-sm">
                <span className="text-xs font-medium">{platformName(task.platform)}</span>
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
