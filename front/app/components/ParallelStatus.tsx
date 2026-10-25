'use client'

import { useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'

interface PlatformStatus {
  isRunning?: boolean
  taskState?: string
  isLoggedIn?: boolean
  lastMessage?: string
  parallelPhase?: string
}

interface PlatformsStatus {
  success?: boolean
  maxParallel?: number
  runningCount?: number
  activeCount?: number
  activePlatforms?: string[]
  platforms?: Record<string, PlatformStatus>
  browsers?: Record<string, { browserReady?: boolean; busy?: boolean }>
}

const PLATFORM_NAMES: Record<string, string> = {
  boss: 'BOSS直聘',
  zhilian: '智联招聘',
  liepin: '猎聘',
  '51job': '前程无忧',
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
  LIMIT_REACHED: '已达上限',
  PLATFORM_LIMIT: '平台上限',
}

const STATE_CLASS: Record<string, string> = {
  RUNNING: 'bg-teal-500/15 text-teal-700 dark:text-teal-300 border-teal-500/40',
  STARTING: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/40',
  STOPPING: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/40',
  PAUSED: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/40',
  FAILED: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/40',
  PLATFORM_LIMIT: 'bg-orange-500/15 text-orange-700 dark:text-orange-300 border-orange-500/40',
  LIMIT_REACHED: 'bg-orange-500/15 text-orange-700 dark:text-orange-300 border-orange-500/40',
}

const DEFAULT_CLASS = 'bg-gray-500/10 text-gray-600 dark:text-gray-300 border-gray-400/30'

/**
 * 并行状态条：显示当前有几个平台在跑、上限多少、每个平台自己的状态。
 * 数据来自 /api/platforms/status，刷新页面后与后端真实状态一致。
 */
export default function ParallelStatus({ refreshKey = 0 }: { refreshKey?: number }) {
  const [data, setData] = useState<PlatformsStatus | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const response = await fetch(`${API_BASE}/api/platforms/status`)
        if (!response.ok) return
        const json = await response.json()
        if (!cancelled) setData(json)
      } catch {
        // 单次请求失败不清空上一次的真实状态
      }
    }
    void load()
    const timer = window.setInterval(load, 2000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [refreshKey])

  if (!data) return null

  const platforms = data.platforms || {}
  const browsers = data.browsers || {}
  const max = data.maxParallel ?? 2
  const running = data.runningCount ?? 0
  const active = data.activeCount ?? 0
  const activeNames = (data.activePlatforms || []).map((p) => PLATFORM_NAMES[p] || p)

  return (
    <div className="rounded-lg border bg-card px-4 py-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p>
          并行运行 <strong>{running}</strong> / {max}
          {active > running && <span className="text-muted-foreground">（含启动/停止中：{active} 个）</span>}
          {activeNames.length > 0 && (
            <span className="text-muted-foreground"> · 占用额度：{activeNames.join('、')}</span>
          )}
        </p>
        <p className="text-xs text-muted-foreground">同一平台同一时间只允许一个任务；达到上限时启动会被明确拒绝</p>
      </div>
      <div className="mt-2 flex flex-wrap gap-2">
        {Object.keys(platforms).map((platform) => {
          const status = platforms[platform] || {}
          const state = status.taskState || 'IDLE'
          const browserReady = browsers[platform]?.browserReady
          return (
            <span
              key={platform}
              className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${STATE_CLASS[state] || DEFAULT_CLASS}`}
              title={status.lastMessage || ''}
            >
              <span className="font-medium">{PLATFORM_NAMES[platform] || platform}</span>
              <span>{STATE_TEXT[state] || state}</span>
              <span className="opacity-70">{browserReady ? '浏览器已开' : '浏览器未开'}</span>
            </span>
          )
        })}
      </div>
    </div>
  )
}
