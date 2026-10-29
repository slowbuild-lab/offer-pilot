---
layout: home
hero:
  name: "求职作战台"
  text: "筛选有依据，投递有记录"
  tagline: "本机管理求职方案与四平台任务，控制节奏，核实结果。"
  actions:
    - theme: brand
      text: 本机启动
      link: /guide/quick-start
    - theme: alt
      text: 查看源码
      link: https://github.com/slowbuild-lab/offer-pilot
features:
  - title: 先预览，再确认
    details: BOSS与智联方案使用版本草稿与只读预览，核对规则和材料后生效。
  - title: 四平台共用保护
    details: 读取、翻页、投递分别限速，配合本机预算与连续休息，异常时暂停。
  - title: 结果如实记录
    details: 区分筛选通过、外发尝试与页面确认；结果不明保留待核实，不自动重发。
  - title: 数据保留在本机
    details: SQLite账本与平台独立浏览器资料便于核对和备份，不随源码分发。
---

## 从一次可核对的任务开始

安装环境与兼容数据库后启动服务，打开平台浏览器登录，核对条件和预算，再手动启动任务。服务启动不会自动投递，也不会创建每日计划。

[了解适配范围](/guide/introduction) · [设置节奏与额度](/guide/config) · [排查异常](/guide/faq)

## 当前界面

![四平台投递节奏设置](/images/workbench-pacing.png)

截图来自本仓库2026-10-29的本机只读验收，不能作为真实投递结果证明。

## 当前边界

四个平台已接入节奏保护，统一求职方案目前仅接BOSS与智联。前程无忧无法确认单岗时暂停，猎聘要求卡片状态确认。每日计划、飞书业务通知和完整无人值守恢复尚未完成。

间隔、预算与额外延迟不是平台公布的安全阈值，不能保证账号不受限制。基础数据库准备要求与其他限制见快速开始。
