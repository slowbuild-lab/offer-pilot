'use client'

import Link from 'next/link'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { platformName, type PreflightResult } from '@/lib/preflight'

/**
 * 预检不通过时的清单。
 * WARN 只做文字提示、不弹这个框；只有 FAIL 才阻止启动。
 */
export default function PreflightDialog({
  result,
  onClose,
  onLogin,
  onForceStart,
}: {
  result: PreflightResult
  onClose: () => void
  onLogin: () => void
  onForceStart?: () => void
}) {
  const failures = result.checks.filter((c) => c.status === 'FAIL')
  const warnings = result.checks.filter((c) => c.status === 'WARN')
  const needsLogin = failures.some((c) => c.action === 'login')
  const needsConfig = failures.some((c) => c.action === 'config')
  const page = result.platform === 'zhilian' ? '/zhilian'
    : result.platform === 'boss' ? '/boss'
    : result.platform === 'liepin' ? '/liepin' : '/51job'

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <Card className="w-full max-w-lg rounded-2xl border-0 shadow-2xl dark:bg-neutral-900">
        <CardHeader className="pb-2">
          <CardTitle className="text-lg">暂时不能启动 {platformName(result.platform)}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4 text-sm">
          <ul className="space-y-2">
            {result.checks.map((check) => (
              <li key={check.id} className="flex gap-3">
                <span
                  className={`mt-0.5 inline-flex h-5 w-10 shrink-0 items-center justify-center rounded text-xs font-medium ${
                    check.status === 'FAIL'
                      ? 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-300'
                      : check.status === 'WARN'
                        ? 'bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300'
                        : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300'
                  }`}
                >
                  {check.status === 'FAIL' ? '未通过' : check.status === 'WARN' ? '提示' : '通过'}
                </span>
                <div>
                  <p className="font-medium">{check.label}</p>
                  <p className="text-muted-foreground">{check.hint}</p>
                </div>
              </li>
            ))}
          </ul>

          {warnings.length > 0 && (
            <p className="text-muted-foreground">以上“提示”项不阻止启动，只作为提醒。</p>
          )}

          <div className="flex flex-wrap justify-end gap-2 pt-2">
            <Button variant="ghost" className="rounded-full px-4" onClick={onClose}>知道了</Button>
            {needsConfig && (
              <Link href={page}>
                <Button variant="outline" className="rounded-full px-4">去配置</Button>
              </Link>
            )}
            {needsLogin && (
              <Button className="rounded-full px-4" onClick={onLogin}>打开浏览器登录</Button>
            )}
            {onForceStart && failures.every((c) => c.action === 'browser') && (
              <Button className="rounded-full px-4" onClick={onForceStart}>仍然启动</Button>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
