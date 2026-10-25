'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { platformName } from '@/lib/preflight'

export interface PlatformView {
  platform: string
  taskState: string
  isRunning: boolean
  isLoggedIn: boolean
  browserReady: boolean
  lastMessage: string
  lastMessageType: string
  browserError?: string
  deliveredCount: number
  verified: boolean
  todayAttempts?: number | null
  remainingToday?: number | null
  unknownCount?: number | null
}

const STATE_TEXT: Record<string, string> = {
  IDLE: '空闲',
  STARTING: '启动中',
  RUNNING: '运行中',
  STOPPING: '停止中',
  STOPPED: '已停止',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  FAILED: '失败',
  LIMIT_REACHED: '已达本次上限',
  PLATFORM_LIMIT: '平台今日上限',
}

const STATE_CLASS: Record<string, string> = {
  RUNNING: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/40',
  STARTING: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/40',
  STOPPING: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/40',
  PAUSED: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/40',
  FAILED: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/40',
  PLATFORM_LIMIT: 'bg-orange-500/15 text-orange-700 dark:text-orange-300 border-orange-500/40',
  LIMIT_REACHED: 'bg-orange-500/15 text-orange-700 dark:text-orange-300 border-orange-500/40',
}

const DEFAULT_STATE_CLASS = 'bg-gray-500/10 text-gray-600 dark:text-gray-300 border-gray-400/30'

export default function PlatformCard({
  view,
  busy,
  onStart,
  onStop,
  onResume,
  onLogin,
  onForceStop,
}: {
  view: PlatformView
  busy: string | null
  onStart: (platform: string) => void
  onStop: (platform: string) => void
  onResume: (platform: string) => void
  onLogin: (platform: string) => void
  onForceStop: (platform: string) => void
}) {
  const state = view.taskState || 'IDLE'
  const stopping = state === 'STOPPING'
  const running = state === 'STARTING' || state === 'RUNNING'
  const paused = state === 'PAUSED'
  const [stoppingSince, setStoppingSince] = useState<number | null>(null)
  const [, tick] = useState(0)

  useEffect(() => {
    if (!stopping) {
      setStoppingSince(null)
      return
    }
    setStoppingSince((prev) => prev ?? Date.now())
    const timer = window.setInterval(() => tick((n) => n + 1), 1000)
    return () => window.clearInterval(timer)
  }, [stopping])

  const canForceStop = Boolean(stopping && stoppingSince && Date.now() - stoppingSince > 15000)
  const pending = busy === view.platform

  return (
    <Card className="rounded-xl">
      <CardContent className="space-y-3 p-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <p className="font-medium">{platformName(view.platform)}</p>
              <span className={`rounded-full border px-2 py-0.5 text-xs ${STATE_CLASS[state] || DEFAULT_STATE_CLASS}`}>
                {STATE_TEXT[state] || state}
              </span>
              {!view.verified && (
                <span className="rounded-full border border-gray-400/30 px-2 py-0.5 text-xs text-muted-foreground">
                  未验证并行
                </span>
              )}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {view.isLoggedIn ? '已登录' : '未登录'} · {view.browserReady ? '浏览器已开' : '浏览器未开'}
              {view.todayAttempts != null && ` · 今日 ${view.todayAttempts}${view.remainingToday != null ? `/${view.todayAttempts + view.remainingToday}` : ''}`}
              {view.todayAttempts == null && view.deliveredCount > 0 && ` · 本次已投 ${view.deliveredCount}`}
              {view.unknownCount != null && view.unknownCount > 0 && ` · 待核实 ${view.unknownCount}`}
            </p>
          </div>
        </div>

        {view.lastMessage && (
          <p className={`text-xs ${view.lastMessageType === 'error' ? 'text-red-600 dark:text-red-400' : view.lastMessageType === 'warning' ? 'text-amber-600 dark:text-amber-400' : 'text-muted-foreground'}`}>
            {view.lastMessage}
          </p>
        )}

        {view.browserError && !view.browserReady && (
          <p className="rounded-md border border-red-300/60 bg-red-50 px-2 py-1 text-xs text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300">
            浏览器启动失败：{view.browserError.length > 120 ? `${view.browserError.slice(0, 120)}…` : view.browserError}
          </p>
        )}

        <div className="flex flex-wrap items-center gap-2">
          {running || stopping ? (
            <Button size="sm" disabled={pending || stopping} onClick={() => onStop(view.platform)}
              className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 px-4 text-white">
              {stopping ? '正在停止…' : state === 'STARTING' ? '停止启动' : '停止投递'}
            </Button>
          ) : paused ? (
            <Button size="sm" disabled={pending} onClick={() => onResume(view.platform)} className="rounded-full px-4">
              继续投递
            </Button>
          ) : !view.isLoggedIn ? (
            <Button size="sm" disabled={pending} onClick={() => onLogin(view.platform)}
              className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 px-4 text-white">
              打开浏览器登录
            </Button>
          ) : (
            <Button size="sm" disabled={pending} onClick={() => onStart(view.platform)}
              className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 px-4 text-white">
              开始投递
            </Button>
          )}

          {view.isLoggedIn && !running && !stopping && (
            <Button size="sm" variant="outline" disabled={pending} onClick={() => onLogin(view.platform)} className="rounded-full px-3">
              打开浏览器
            </Button>
          )}

          <Link href={view.platform === '51job' ? '/51job' : `/${view.platform}`}>
            <Button size="sm" variant="ghost" className="rounded-full px-3">查看配置</Button>
          </Link>

          {canForceStop && (
            <Button size="sm" variant="outline" onClick={() => onForceStop(view.platform)}
              className="rounded-full border-amber-500 px-3 text-amber-600">
              强制结束
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
