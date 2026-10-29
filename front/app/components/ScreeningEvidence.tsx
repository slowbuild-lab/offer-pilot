'use client'

import type { RuleCheck } from '@/lib/screening'

function EvidenceText({ text, term }: { text: string; term: string }) {
  const at = term ? text.indexOf(term) : -1
  if (at < 0) return <>{text}</>
  return <>{text.slice(0, at)}<mark className="rounded bg-amber-200/70 px-0.5 text-inherit dark:bg-amber-500/30">{text.slice(at, at + term.length)}</mark>{text.slice(at + term.length)}</>
}
export default function ScreeningEvidence({ checks }: { checks?: RuleCheck[] }) {
  if (!checks?.length) return null
  return <details className="rounded-xl border border-border bg-background/40 p-3 text-sm">
    <summary className="cursor-pointer font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">查看筛选依据（{checks.length} 项）</summary>
    <ol className="mt-3 space-y-3">
      {checks.map((check, i) => <li key={`${check.code}-${i}`} className="space-y-2 border-t border-border pt-3">
        <div className="flex flex-wrap items-start justify-between gap-2"><span className="font-medium">{check.label}</span><span className={`rounded-full px-2 py-0.5 text-xs ${check.passed ? 'bg-primary/10 text-primary' : 'bg-amber-500/10 text-amber-800 dark:text-amber-300'}`}>{check.passed ? '通过' : '未通过'}</span></div>
        <p className="break-words text-xs text-muted-foreground">{check.reason}</p>
        {check.evidence && <blockquote className="whitespace-pre-wrap break-words border-l-2 border-primary/30 pl-3 text-xs leading-relaxed"><EvidenceText text={check.evidence} term={check.term} /></blockquote>}
      </li>)}
    </ol>
    <p className="mt-3 text-xs text-muted-foreground">工作内容依据来自岗位详情；公司依据来自平台公司名称。仅展示当时已评估的规则。</p>
  </details>
}
