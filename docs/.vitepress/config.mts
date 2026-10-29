import { defineConfig } from 'vitepress'

const repository = 'https://github.com/slowbuild-lab/offer-pilot'
export default defineConfig({
  lang: 'zh-CN',
  title: '求职领航',
  description: '本机求职方案管理、BOSS精细筛选、四平台节奏保护与投递结果核实。',
  base: process.env.DOCS_BASE || (process.env.GITHUB_ACTIONS ? '/offer-pilot/' : '/'),
  lastUpdated: true,
  head: [
    ['meta', { name: 'author', content: 'slowbuild-lab' }],
    ['meta', { name: 'keywords', content: '求职领航, OfferPilot, 求职方案, 投递记录, BOSS直聘, 智联招聘, 前程无忧, 猎聘' }]
  ],
  vite: { server: { watch: { ignored: ['**/.vitepress/dist/**'] } } },
  themeConfig: {
    siteTitle: '求职领航',
    search: { provider: 'local' },
    nav: [
      { text: '首页', link: '/' },
      { text: '使用指南', link: '/guide/quick-start' },
      { text: '条件与保护', link: '/guide/config' },
      { text: '常见问题', link: '/guide/faq' },
      { text: '源码', link: repository }
    ],
    sidebar: [
      { text: '了解与使用', items: [
        { text: '项目介绍', link: '/guide/introduction' },
        { text: '本机启动', link: '/guide/quick-start' },
        { text: '求职条件与投递保护', link: '/guide/config' },
        { text: '当前界面', link: '/guide/screenshots' }
      ] },
      { text: '维护与边界', items: [
        { text: '模型与通知', link: '/guide/ai-and-push' },
        { text: '常见问题', link: '/guide/faq' },
        { text: '更新记录', link: '/guide/changelog' },
        { text: '反馈与贡献', link: '/guide/community' },
        { text: '开发资料', link: '/guide/resources' }
      ] }
    ],
    editLink: { pattern: repository + '/edit/main/docs/:path', text: '修改本页' },
    socialLinks: [{ icon: 'github', link: repository }],
    footer: { message: 'PolyForm Noncommercial 1.0.0 · 来源与授权见仓库 NOTICE / LICENSE。', copyright: '求职领航 · slowbuild-lab 维护' },
    docFooter: { prev: '上一篇', next: '下一篇' },
    outline: { label: '本页内容', level: [2, 3] },
    lastUpdated: { text: '最后更新' }
  }
})
