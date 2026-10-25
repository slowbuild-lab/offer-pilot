package com.getjobs.worker.boss;

import com.microsoft.playwright.Page;
import java.util.List;

/** Counts only own messages, including chat iframes; existing messages never prove a new send. */
final class BossMessageEvidence {
    private static final String COUNT="""
        args => [...document.querySelectorAll('li.message-item.item-myself')].filter(item => {
          const content = item.querySelector('.message-content');
          if (!content || content.innerText.trim() !== args[0]) return false;
          if (!args[1]) return true;
          const classes = [item.className, ...[...item.querySelectorAll('[class]')].map(el => el.className)].join(' ');
          return !/发送中|发送失败|重新发送|点击重试/.test(item.innerText)
            && !/send-fail|send-error|message-fail|sending|pending|loading/.test(classes);
        }).length
        """;
    static int count(Page page,String message,boolean confirmedOnly) {
        int maximum=0;
        for (var frame:page.frames()) {
            try {
                Object found=frame.evaluate(COUNT,List.of(message,confirmedOnly));
                if(found instanceof Number n) maximum=Math.max(maximum,n.intValue());
            } catch (com.microsoft.playwright.PlaywrightException ignored) { /* A destroyed frame cannot confirm a send. */ }
        }
        return maximum;
    }
    static boolean waitForNew(Page page,String message,int before) {
        long deadline=System.nanoTime()+10_000_000_000L;
        do {
            if(page.isClosed()) return false;
            if(count(page,message,true)>before) return true;
            page.waitForTimeout(200);
        } while(System.nanoTime()<deadline);
        return false;
    }
}
