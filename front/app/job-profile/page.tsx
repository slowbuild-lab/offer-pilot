'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { API_BASE } from '@/lib/api'

type Rules = { name: string; platforms: string[]; keywords: string[]; city: string; minimumMonthlySalary: number | null; salaryHard: boolean; excludeCompanies: string[]; excludeJobs: string[]; bossMessage: string; bossImage: boolean; zhilianResumeConfirmed: boolean }
type Snapshot = { version: number; rules: Rules; confirmedAt: number | null }
type Preview = { job: { platform: string; jobId: string; title: string; company: string; city: string; salary: string }; eligible: boolean; reason: string }
const empty: Rules = { name: '', platforms: ['boss', 'zhilian'], keywords: [], city: '', minimumMonthlySalary: null, salaryHard: true, excludeCompanies: [], excludeJobs: [], bossMessage: '', bossImage: false, zhilianResumeConfirmed: false }
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
    if (data.draft) setRules(data.draft.rules)
    setDirty(false)
  }
  useEffect(() => {
    setBusy('加载中')
    Promise.all([load(), request('/cities').then(setCities)]).catch(e => setError(e instanceof Error ? e.message : '加载失败')).finally(() => setBusy(''))
  }, [])
  const change = <K extends keyof Rules>(key: K, value: Rules[K]) => { setRules(r => ({ ...r, [key]: value })); setDirty(true); setNotice('') }
  const act = async (kind: 'save' | 'preview' | 'confirm') => {
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
  const input = 'mt-1 w-full rounded border bg-background p-2'
  const canPreview = !!draft && !draft.confirmedAt && !dirty && !busy
  return <div className="mx-auto min-w-0 max-w-5xl space-y-6 p-4 md:p-8">
    <header><h1 className="text-2xl font-semibold">求职方案</h1><p className="mt-2 text-sm text-muted-foreground">一次确认，后续新任务复用。预览只读；保存草稿不会改变已生效方案。</p></header>
    <nav aria-label="方案导航" className="flex flex-wrap gap-4 text-sm"><Link href="/">返回作战台</Link><Link href="/delivery-records">投递记录与额度</Link><Link href="/boss">BOSS 登录与图片简历</Link><Link href="/zhilian">智联登录与平台简历</Link></nav>
    <section className="space-y-2 rounded-lg border p-4"><p>当前生效：{active ? `${active.rules.name} · v${active.version}` : '尚未确认统一方案，平台原有手动配置仍保留'}</p><p className="text-sm text-muted-foreground">{draft ? `最近版本 v${draft.version}：${draft.confirmedAt ? '已确认；编辑后保存会创建新版本' : '待预览确认'}` : '请填写自己的求职条件，不会根据历史资料代填。'}</p><p className="text-sm">阶段 A：方案、预览、账本与额度。每日定时和飞书通知将在后续阶段接入。</p></section>
    {error && <p role="alert" className="rounded border border-red-400 p-3 text-red-600 dark:text-red-300">{error}</p>}
    <div role="status" aria-live="polite">{busy || notice}</div>
    <fieldset disabled={!!busy} className="grid gap-5 rounded-lg border p-5 md:grid-cols-2">
      <legend className="px-2 font-medium">岗位条件与材料</legend>
      <label htmlFor="profile-name">方案名称<input id="profile-name" maxLength={30} value={rules.name} onChange={e => change('name', e.target.value)} className={input} /></label>
      <label htmlFor="profile-city">目标城市（单城市）<input id="profile-city" list="profile-cities" maxLength={50} value={rules.city} onChange={e => change('city', e.target.value)} className={input} /><datalist id="profile-cities">{cities.map(city => <option key={city} value={city} />)}</datalist></label>
      <label htmlFor="profile-keywords">岗位关键词（每行一项，最多 10 项）<textarea id="profile-keywords" rows={4} value={rules.keywords.join('\n')} onChange={e => change('keywords', e.target.value.split('\n'))} className={input} onBlur={e => change('keywords', lines(e.target.value))} /></label>
      <div className="space-y-3"><p>所选平台</p>{['boss', 'zhilian'].map(p => <label key={p} className="flex items-center gap-2"><input type="checkbox" checked={rules.platforms.includes(p)} onChange={e => change('platforms', e.target.checked ? [...rules.platforms, p] : rules.platforms.filter(v => v !== p))} />{p === 'boss' ? 'BOSS 直聘' : '智联招聘'}</label>)}<p className="text-sm text-muted-foreground">原平台黑名单仍生效。当前版本未提供统一经验/学历硬性字段，不会伪称已校验。</p></div>
      <label htmlFor="profile-salary">最低期望月固定薪资（元/月，可留空）<input id="profile-salary" type="number" min={0} step={1} value={rules.minimumMonthlySalary ?? ''} onChange={e => change('minimumMonthlySalary', e.target.value === '' ? null : Number(e.target.value))} className={input} /></label>
      <label className="flex items-center gap-2"><input type="checkbox" checked={rules.salaryHard} onChange={e => change('salaryHard', e.target.checked)} />薪资作为硬性条件；面议、跨越底线、年薪无法确认时跳过</label>
      <label htmlFor="profile-companies">排除公司（每行包含词）<textarea id="profile-companies" rows={3} value={rules.excludeCompanies.join('\n')} onChange={e => change('excludeCompanies', e.target.value.split('\n'))} onBlur={e => change('excludeCompanies', lines(e.target.value))} className={input} /></label>
      <label htmlFor="profile-jobs">排除岗位（每行包含词）<textarea id="profile-jobs" rows={3} value={rules.excludeJobs.join('\n')} onChange={e => change('excludeJobs', e.target.value.split('\n'))} onBlur={e => change('excludeJobs', lines(e.target.value))} className={input} /></label>
      {rules.platforms.includes('boss') && <div className="space-y-3 md:col-span-2"><label htmlFor="profile-message">本人确认的 BOSS 招呼语<textarea id="profile-message" rows={3} maxLength={2000} value={rules.bossMessage} onChange={e => change('bossMessage', e.target.value)} className={input} /></label><label className="flex items-center gap-2"><input type="checkbox" checked={rules.bossImage} onChange={e => change('bossImage', e.target.checked)} />发送 BOSS 页面已上传的图片简历（更换后须新版本预览确认）</label><p className="text-sm text-muted-foreground">此方案关闭重复沟通与 AI 生成话术；不改写原平台保存的配置。</p></div>}
      {rules.platforms.includes('zhilian') && <label className="flex items-center gap-2 md:col-span-2"><input type="checkbox" checked={rules.zhilianResumeConfirmed} onChange={e => change('zhilianResumeConfirmed', e.target.checked)} />我已在智联平台核对本次使用的简历；此处不会替换其在线简历</label>}
    </fieldset>
    <div className="flex flex-wrap gap-3"><button disabled={!!busy} onClick={() => void act('save')} className="rounded border px-4 py-2 disabled:opacity-50">保存为新草稿</button><button disabled={!canPreview} onClick={() => void act('preview')} className="rounded border px-4 py-2 disabled:opacity-50">只读预览</button><button disabled={!canPreview || preview.length === 0} onClick={() => void act('confirm')} className="rounded bg-primary px-4 py-2 text-primary-foreground disabled:opacity-50">确认并应用此版本</button></div>
    {dirty && <p className="text-sm">有未保存修改，请先保存新草稿。当前生效版本未改变。</p>}
    {preview.length > 0 && <section aria-label="只读预览" className="space-y-3"><h2 className="text-lg font-medium">预览：{preview.filter(p => p.eligible).length} 条符合条件 / {preview.length} 条已采集</h2><p className="text-sm text-muted-foreground">结果仅为采集时快照。正式执行仍重新检查条件、去重与额度；未符合不自动放宽条件。</p>{preview.map((p, i) => <article key={`${p.job.platform}-${p.job.jobId}-${i}`} className="space-y-1 rounded border p-4"><h3 className="font-medium">{p.job.title} · {p.job.company}</h3><p className="text-sm">{p.job.platform} · {p.job.city || '城市缺失'} · {p.job.salary || '薪资缺失'}</p><p className="text-sm">{p.eligible ? '可候选' : '跳过'}：{p.reason}</p></article>)}</section>}
  </div>
}
