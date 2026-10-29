'use client'

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import type { BossFilters } from '@/lib/screening'

const fields: { key: keyof BossFilters; label: string; hint: string; placeholder: string }[] = [
  { key: 'includeCompanies', label: '公司白名单', hint: '留空不限制；名称包含任一词即可。公司排除项和黑名单仍优先。', placeholder: '输入希望考虑的公司或品牌关键词' },
  { key: 'descriptionInclude', label: '工作内容至少包含一项', hint: '留空不限制；每行一个词，命中任一项即可。', placeholder: '大模型\n智能体' },
  { key: 'descriptionExclude', label: '工作内容排除项', hint: '命中任一词即跳过。启用工作内容条件后，缺失详情不会放行。', placeholder: '外包\n销售' },
]
const lines = (value: string) => value.split('\n').map(v => v.trim()).filter(Boolean)
const digits = (value: string) => (/^\d*$/.test(value) ? (value === '' ? null : Number(value)) : undefined)

export default function BossAdvancedFilters({ value, cooldownDays, enabled, onChange, onCooldownChange }: {
  value: BossFilters
  cooldownDays: number | null
  enabled: boolean
  onChange: (value: BossFilters) => void
  onCooldownChange: (value: number | null) => void
}) {
  return <Card>
    <CardHeader><CardTitle role="heading" aria-level={2}>BOSS 高级筛选</CardTitle><CardDescription>仅 BOSS 生效；智联继续使用上方基础条件。每项最多 20 行，每行最多 100 字。</CardDescription></CardHeader>
    <CardContent className="space-y-5">
      {!enabled && <p role="status" className="text-sm text-amber-800 dark:text-amber-300">尚未选择 BOSS。若填写高级条件或冷却期，请选择 BOSS 后保存，或先清空这些条件。</p>}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">{fields.map(field => <div key={field.key} className="min-w-0 space-y-2">
        <Label htmlFor={`boss-${field.key}`}>{field.label}</Label>
        <Textarea id={`boss-${field.key}`} rows={4} maxLength={2200} value={value[field.key].join('\n')} placeholder={field.placeholder}
          onChange={e => onChange({ ...value, [field.key]: e.target.value.split('\n') })}
          onBlur={e => onChange({ ...value, [field.key]: lines(e.target.value) })}
          aria-describedby={`boss-${field.key}-hint`} className="rounded-xl border-white/40 bg-white/5 px-4 py-3 focus-visible:ring-emerald-400/40" />
        <p id={`boss-${field.key}-hint`} className="text-xs leading-relaxed text-muted-foreground">{field.hint}</p>
      </div>)}</div>
      <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
        <div className="min-w-0 space-y-2">
          <Label htmlFor="boss-hr-cooldown">HR 冷却期（天）</Label>
          <Input id="boss-hr-cooldown" type="number" inputMode="numeric" min={1} max={365} step={1} placeholder="留空即不启用"
            value={cooldownDays ?? ''}
            onChange={e => { const parsed = digits(e.target.value); if (parsed !== undefined) onCooldownChange(parsed) }}
            aria-describedby="boss-hr-cooldown-hint"
            className="rounded-xl border-white/40 bg-white/5 px-4 focus-visible:ring-emerald-400/40" />
          <p id="boss-hr-cooldown-hint" className="text-xs leading-relaxed text-muted-foreground">同一 HR 在设定天数内只接触一次，换岗位同样生效。取不到 HR 标识时不放行；留空表示不启用。</p>
        </div>
      </div>
      <p className="text-xs leading-relaxed text-muted-foreground">按普通文本匹配；可识别“不是外包”“无需销售”等明确否定，并区分“销售系统/软件/工具”。复杂表述请核对预览原文，这不是完整的语义分析。原 BOSS 黑名单与 HR 活跃过滤继续生效，并显示其来源。</p>
    </CardContent>
  </Card>
}
