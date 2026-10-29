'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { API_BASE } from '@/lib/api'
import { BiUserCircle, BiSave, BiBriefcase, BiMessageSquareDetail, BiInfoCircle, BiCheckCircle, BiShow } from 'react-icons/bi'
import PageHeader from '@/app/components/PageHeader'
import BossAdvancedFilters from '@/app/components/BossAdvancedFilters'
import ScreeningEvidence from '@/app/components/ScreeningEvidence'
import { emptyBossFilters, emptyContactPolicy, type BossFilters, type ContactPolicy, type RuleCheck } from '@/lib/screening'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

const primaryButton = 'rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300'
const textareaStyle = 'rounded-xl border-white/40 bg-white/5 px-4 py-3 shadow-[inset_0_1px_0_rgba(255,255,255,.25)] focus-visible:ring-emerald-400/40'
const checkboxStyle = 'h-4 w-4 shrink-0 accent-blue-600'

type Rules = { name: string; platforms: string[]; keywords: string[]; city: string; minimumMonthlySalary: number | null; salaryHard: boolean; excludeCompanies: string[]; excludeJobs: string[]; bossMessage: string; bossImage: boolean; zhilianResumeConfirmed: boolean; bossFilters: BossFilters; contactPolicy: ContactPolicy }
type Snapshot = { version: number; rules: Rules; confirmedAt: number | null }
type Preview = { job: { platform: string; jobId: string; title: string; company: string; city: string; salary: string }; eligible: boolean; reason: string; checks?: RuleCheck[] }
const empty: Rules = { name: '', platforms: ['boss', 'zhilian'], keywords: [], city: '', minimumMonthlySalary: null, salaryHard: true, excludeCompanies: [], excludeJobs: [], bossMessage: '', bossImage: false, zhilianResumeConfirmed: false, bossFilters: emptyBossFilters(), contactPolicy: emptyContactPolicy() }
const lines = (value: string) => value.split('\n').map(s => s.trim()).filter(Boolean)
async function request(path: string, init?: RequestInit) {
  const res = await fetch(`${API_BASE}/api/job-profile${path}`, init)
  const data = await res.json()
  if (!res.ok) throw new Error(data.message || '操作未完成，请检查服务')
  return data
}
export default function JobProfilePage() {
  const [rules, setRules] = useState<Rules>(empty)
  const [draft, setDraft] = useState<Snapshot | null>(null)
  const [active, setActive] = useState<Snapshot | null>(null)
  const [cities, setCities] = useState<string[]>([])
  const [preview, setPreview] = useState<Preview[]>([])
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const load = async () => {
    const data = await request('')
    setDraft(data.draft); setActive(data.active); setPreview(data.preview)
    if (data.draft) setRules({ ...data.draft.rules, bossFilters: { ...emptyBossFilters(), ...data.draft.rules.bossFilters }, contactPolicy: { ...emptyContactPolicy(), ...data.draft.rules.contactPolicy } })
    setDirty(false)
  }
  useEffect(() => {
    setBusy('加载中')
    Promise.all([load(), request('/cities').then(setCities)]).catch(e => setError(e instanceof Error ? e.message : '加载失败')).finally(() => setBusy(''))
  }, [])
  const change = <K extends keyof Rules>(key: K, value: Rules[K]) => { setRules(r => ({ ...r, [key]: value })); setDirty(true); setNotice('') }
  const act = async (kind: 'save' | 'preview' | 'confirm') => {
    if (kind === 'save') {
      const advanced = Object.values(rules.bossFilters).map(values => values.map(v => v.trim()).filter(Boolean))
      if (advanced.some(values => values.length > 20 || values.some(v => v.length > 100))) { setError('BOSS高级条件每项最多 20 行，每行最多 100 字'); return }
      if (!rules.platforms.includes('boss') && advanced.some(values => values.length > 0)) { setError('请先选择 BOSS 平台，或清空 BOSS 高级条件'); return }
      const days = rules.contactPolicy.hrCooldownDays
      if (days != null && (!Number.isInteger(days) || days < 1 || days > 365)) { setError('HR 冷却期必须为 1 到 365 天'); return }
      if (days != null && !rules.platforms.includes('boss')) { setError('HR 冷却期需要选择 BOSS 平台，或清空该天数'); return }
    }
    if (kind === 'save' && rules.minimumMonthlySalary != null && (!Number.isSafeInteger(rules.minimumMonthlySalary) || rules.minimumMonthlySalary < 0 || rules.minimumMonthlySalary > 100000000)) { setError('最低月薪必须为有效的非负整数'); return }
    if (kind === 'confirm' && !window.confirm('确认这些条件及材料用于所选平台下一次新任务？此操作不会立即投递，也不会开启定时计划。')) return
    setBusy(kind === 'preview' ? '正在只读采集，请保持平台浏览器可用（每个平台最多约两分钟）' : '处理中'); setError(''); setNotice('')
    try {
      if (kind === 'save') {
        await request('/versions', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(rules) })
        await load(); setNotice('已保存新草稿，原生效版本不变。请预览后确认。')
      } else if (draft) {
        if (kind === 'preview') {
          setPreview([])
          const data = await request(`/${draft.version}/preview`, { method: 'POST' }); setPreview(data.items)
          setNotice('预览只读，未发送申请、招呼或附件。请检查实际匹配结果后确认。')
        } else {
          await request(`/${draft.version}/confirm`, { method: 'POST' }); await load()
          setNotice('方案已生效，下一次新任务使用此版本。阶段 A 尚未提供定时与飞书通知。')
        }
      }
    } catch (e) { setError(e instanceof Error ? e.message : '操作失败') } finally { setBusy('') }
  }
  const canPreview = !!draft && !draft.confirmedAt && !dirty && !busy
  return <div className="space-y-6">
    <PageHeader
      icon={<BiUserCircle className="text-2xl" />}
      title="求职方案"
      subtitle="统一管理求职条件与投递材料，预览确认后用于新任务"
      actions={<Button disabled={!!busy} onClick={() => void act('save')} size="sm" className={primaryButton}><BiSave className="mr-1" />保存为新草稿</Button>}
    />
    <nav aria-label="方案导航" className="flex flex-wrap gap-2">
      {[["/", "返回作战台"], ["/delivery-records", "投递记录与额度"], ["/boss", "BOSS 登录与图片简历"], ["/zhilian", "智联登录与平台简历"]].map(([href, title]) => <Button key={href} asChild size="sm" variant="ghost" className="rounded-full text-muted-foreground"><Link href={href}>{title}</Link></Button>)}
    </nav>
    <Card className="border-primary/20 bg-primary/5">
      <CardContent className="flex items-start gap-3 pt-6">
        <BiInfoCircle className="mt-0.5 h-5 w-5 shrink-0 text-primary" />
        <div className="min-w-0 space-y-2">
          <p className="text-sm font-medium">当前生效：{active ? `${active.rules.name} · v${active.version}` : '尚未确认统一方案，平台原有手动配置仍保留'}</p>
          <p className="text-xs text-muted-foreground">{draft ? `最近版本 v${draft.version}：${draft.confirmedAt ? '已确认；编辑后保存会创建新版本' : '待预览确认'}` : '请填写自己的求职条件，不会根据历史资料代填。'}</p>
          <p className="text-xs text-muted-foreground">保存草稿不会改变生效方案。当前提供方案、预览、账本与额度；每日定时和飞书通知将在后续阶段接入。</p>
        </div>
      </CardContent>
    </Card>
    {error && <Card role="alert" className="border-red-500/20 bg-red-500/5"><CardContent className="pt-6 text-sm text-red-600 dark:text-red-300">{error}</CardContent></Card>}
    <div role="status" aria-live="polite" className={busy || notice ? 'rounded-xl border border-blue-500/20 bg-blue-500/5 p-4 text-sm text-foreground' : 'sr-only'}>{busy || notice}</div>
    <fieldset disabled={!!busy} className="min-w-0 space-y-6">
      <legend className="sr-only">岗位条件与材料</legend>
      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><BiBriefcase className="text-primary" />岗位条件</CardTitle><CardDescription>设置目标岗位、城市和筛选条件，两个平台使用同一份已确认方案</CardDescription></CardHeader>
        <CardContent className="grid grid-cols-1 gap-6 md:grid-cols-2">
          <div className="space-y-2"><Label htmlFor="profile-name">方案名称</Label><Input id="profile-name" maxLength={30} value={rules.name} onChange={e => change('name', e.target.value)} placeholder="例如：北京产品经理求职方案" /></div>
          <div className="space-y-2"><Label htmlFor="profile-city">目标城市（单城市）</Label><Input id="profile-city" list="profile-cities" maxLength={50} value={rules.city} onChange={e => change('city', e.target.value)} placeholder="选择或输入目标城市" /><datalist id="profile-cities">{cities.map(city => <option key={city} value={city} />)}</datalist></div>
          <div className="space-y-2"><Label htmlFor="profile-keywords">岗位关键词（每行一项，最多 10 项）</Label><Textarea id="profile-keywords" rows={4} value={rules.keywords.join('\n')} onChange={e => change('keywords', e.target.value.split('\n'))} className={textareaStyle} onBlur={e => change('keywords', lines(e.target.value))} placeholder="产品经理&#10;AI 产品经理" /></div>
          <div className="space-y-3"><Label>所选平台</Label><div className="flex flex-wrap gap-3">{['boss', 'zhilian'].map(p => <label key={p} className="flex cursor-pointer items-center gap-2 rounded-full border border-white/40 bg-white/5 px-4 py-2 text-sm"><input type="checkbox" className={checkboxStyle} checked={rules.platforms.includes(p)} onChange={e => change('platforms', e.target.checked ? [...rules.platforms, p] : rules.platforms.filter(v => v !== p))} />{p === 'boss' ? 'BOSS 直聘' : '智联招聘'}</label>)}</div><p className="text-xs text-muted-foreground">原平台黑名单仍生效。当前版本未提供统一经验/学历硬性字段，不会伪称已校验。</p></div>
          <div className="space-y-2"><Label htmlFor="profile-salary">最低期望月固定薪资（元/月，可留空）</Label><Input id="profile-salary" type="number" min={0} step={1} value={rules.minimumMonthlySalary ?? ''} onChange={e => change('minimumMonthlySalary', e.target.value === '' ? null : Number(e.target.value))} placeholder="留空则不限制月薪" /></div>
          <label className="flex items-start gap-3 rounded-xl border border-white/20 bg-white/5 p-4 text-sm"><input type="checkbox" className={`${checkboxStyle} mt-0.5`} checked={rules.salaryHard} onChange={e => change('salaryHard', e.target.checked)} /><span>薪资作为硬性条件<span className="mt-1 block text-xs text-muted-foreground">面议、跨越底线、年薪无法确认时跳过</span></span></label>
          <div className="space-y-2"><Label htmlFor="profile-companies">排除公司（每行包含词）</Label><Textarea id="profile-companies" rows={3} value={rules.excludeCompanies.join('\n')} onChange={e => change('excludeCompanies', e.target.value.split('\n'))} onBlur={e => change('excludeCompanies', lines(e.target.value))} className={textareaStyle} placeholder="输入不考虑的公司关键词" /></div>
          <div className="space-y-2"><Label htmlFor="profile-jobs">排除岗位（每行包含词）</Label><Textarea id="profile-jobs" rows={3} value={rules.excludeJobs.join('\n')} onChange={e => change('excludeJobs', e.target.value.split('\n'))} onBlur={e => change('excludeJobs', lines(e.target.value))} className={textareaStyle} placeholder="输入不考虑的岗位关键词" /></div>
        </CardContent>
      </Card>
      <BossAdvancedFilters value={rules.bossFilters} cooldownDays={rules.contactPolicy.hrCooldownDays} enabled={rules.platforms.includes('boss')}
        onChange={value => change('bossFilters', value)} onCooldownChange={value => change('contactPolicy', { hrCooldownDays: value })} />
      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><BiMessageSquareDetail className="text-primary" />投递材料</CardTitle><CardDescription>核对招呼语与简历；更换材料后需要重新保存、预览和确认</CardDescription></CardHeader>
        <CardContent className="space-y-6">
          {rules.platforms.includes('boss') && <div className="space-y-4"><div className="space-y-2"><Label htmlFor="profile-message">本人确认的 BOSS 招呼语</Label><Textarea id="profile-message" rows={4} maxLength={2000} value={rules.bossMessage} onChange={e => change('bossMessage', e.target.value)} className={textareaStyle} placeholder="填写希望用于本次求职的招呼语" /></div><label className="flex items-start gap-3 text-sm"><input type="checkbox" className={`${checkboxStyle} mt-0.5`} checked={rules.bossImage} onChange={e => change('bossImage', e.target.checked)} />发送 BOSS 页面已上传的图片简历（更换后须新版本预览确认）</label><p className="text-xs text-muted-foreground">此方案关闭重复沟通与 AI 生成话术；不改写原平台保存的配置。</p></div>}
          {rules.platforms.includes('zhilian') && <label className="flex items-start gap-3 text-sm"><input type="checkbox" className={`${checkboxStyle} mt-0.5`} checked={rules.zhilianResumeConfirmed} onChange={e => change('zhilianResumeConfirmed', e.target.checked)} />我已在智联平台核对本次使用的简历；此处不会替换其在线简历</label>}
          {rules.platforms.length === 0 && <p className="text-sm text-muted-foreground">请选择至少一个投递平台，再核对对应材料。</p>}
        </CardContent>
      </Card>
    </fieldset>
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2"><BiCheckCircle className="text-primary" />预览与确认</CardTitle><CardDescription>保存新草稿后只读采集岗位，确认版本不会立即投递或开启定时计划</CardDescription></CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-3"><Button disabled={!canPreview} onClick={() => void act('preview')} variant="outline" className="rounded-full px-4"><BiShow />只读预览</Button><Button disabled={!canPreview || preview.length === 0} onClick={() => void act('confirm')} className={primaryButton}><BiCheckCircle />确认并应用此版本</Button></div>
        {dirty && <p className="text-sm text-amber-700 dark:text-amber-300">有未保存修改，请先保存新草稿。当前生效版本未改变。</p>}
        {preview.length === 0 && <p className="text-xs text-muted-foreground">尚无预览结果。请先保存草稿，再进行只读预览。</p>}
      </CardContent>
    </Card>
    {preview.length > 0 && <Card aria-label="只读预览"><CardHeader><CardTitle role="heading" aria-level={2} className="flex items-center gap-2"><BiShow className="text-primary" />预览：{preview.filter(p => p.eligible).length} 条符合条件 / {preview.length} 条已采集</CardTitle><CardDescription>结果仅为采集时快照。正式执行仍重新检查条件、去重与额度；未符合不自动放宽条件。</CardDescription></CardHeader><CardContent className="space-y-3">{preview.map((p, i) => <article key={`${p.job.platform}-${p.job.jobId}-${i}`} className="space-y-2 rounded-xl border border-white/20 bg-white/5 p-4"><div className="flex flex-wrap items-start justify-between gap-2"><h3 className="break-words text-sm font-medium">{p.job.title} · {p.job.company}</h3><span className={`rounded-full px-3 py-1 text-xs ${p.eligible ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300' : 'bg-amber-500/10 text-amber-700 dark:text-amber-300'}`}>{p.eligible ? '可候选' : '跳过'}</span></div><p className="text-xs text-muted-foreground">{p.job.platform} · {p.job.city || '城市缺失'} · {p.job.salary || '薪资缺失'}</p><p className="text-sm">{p.reason}</p><ScreeningEvidence checks={p.checks} /></article>)}</CardContent></Card>}
  </div>
}
