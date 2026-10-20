package com.getjobs.worker.zhilian;

import com.microsoft.playwright.Page;

/** Read-only checks; verification and login must be handled by the user. */
public final class ZhilianPageGuard {
    public enum Block {
        NONE(""), CAPTCHA("页面要求安全验证，请在智联窗口人工处理后继续"),
        LOGIN_REQUIRED("智联登录已失效，请重新登录后继续"),
        PLATFORM_LIMIT("平台提示今日投递已达上限，任务已结束"),
        UNKNOWN_DIALOG("页面出现未识别的弹窗，请查看智联窗口后继续");
        public final String message;
        Block(String message) { this.message = message; }
    }
    private ZhilianPageGuard() {}

    public static Block inspect(Page page) {
        String result = (String) page.evaluate("""
                () => {
                  const visible = e => {
                    const rect = e.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) return false;
                    for (let node = e; node; node = node.parentElement) {
                      const style = getComputedStyle(node);
                      if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0)
                        return false;
                    }
                    return true;
                  };
                  const verification = [...document.querySelectorAll(
                    '[class*="captcha"], [id*="captcha"], iframe[src*="captcha"], [class*="geetest"]')];
                  if (verification.some(visible)) return 'CAPTCHA';
                  if (/passport\\.|\\/login(?:[/?#]|$)/i.test(location.href)) return 'LOGIN_REQUIRED';
                  const notices = [...document.querySelectorAll(
                    '[role="dialog"], [role="alert"], .a-modal, .a-message, .deliver-greeting-modal, .a-job-apply-workflow')]
                    .filter(visible);
                  for (const e of notices) {
                    const text = e.innerText || '';
                    if (/今日.*投递.*上限|投递次数.*上限|投递.*达到上限/.test(text)) return 'PLATFORM_LIMIT';
                    if (/重新登录|登录已失效|登录已过期|请先登录/.test(text)) return 'LOGIN_REQUIRED';
                    if (/安全验证|滑动验证|操作频繁|访问过于频繁|异常访问/.test(text)) return 'CAPTCHA';
                  }
                  const dialogs = notices.filter(e => e.matches('[role="dialog"], .a-modal, .deliver-greeting-modal'));
                  if (dialogs.some(e => !/已向对方发送简历和打招呼语|投递成功|申请成功/.test(e.innerText)))
                    return 'UNKNOWN_DIALOG';
                  return 'NONE';
                }
                """);
        return Block.valueOf(result);
    }
}
