package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playwright 诊断控制器。
 *
 * <p>浏览器现在按平台隔离，所以所有诊断都显式指定平台（默认 boss），
 * 并且全部通过 {@link PlaywrightManager#callOn} 在对应平台的线程上执行。</p>
 */
@RestController
@RequestMapping("/api/playwright")
public class PlaywrightController {

    private final PlaywrightManager playwrightManager;

    public PlaywrightController(PlaywrightManager playwrightManager) {
        this.playwrightManager = playwrightManager;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("initialized", playwrightManager.isInitialized());
        status.put("cdpPort", playwrightManager.getCdpPort());
        status.put("hasBossPage", playwrightManager.getBossPage() != null);
        status.put("hasBrowser", playwrightManager.getContext("boss") != null);
        status.put("bossLoggedIn", playwrightManager.isLoggedIn("boss"));
        status.put("platforms", playwrightManager.browserStatus());
        return ResponseEntity.ok(status);
    }

    /**
     * 指纹自检：确认当前浏览器有没有暴露自动化特征。
     * <p>
     * 期望值：navigator.webdriver = false；userAgentData 存在且与 UA 自洽；cdc_ 残留为空。
     */
    @GetMapping("/fingerprint")
    public ResponseEntity<Map<String, Object>> fingerprint(
            @RequestParam(value = "platform", defaultValue = "boss") String platform) {
        try {
            playwrightManager.ensureReady(platform);
            Object raw = playwrightManager.callOn(platform, page -> page.evaluate("""
                    () => {
                        const uaData = navigator.userAgentData;
                        return {
                            'navigator.webdriver': String(navigator.webdriver),
                            'userAgent': navigator.userAgent,
                            'navigator.platform': navigator.platform,
                            'uaData.platform': uaData ? uaData.platform : '(缺失-异常)',
                            'uaData.brands': uaData && uaData.brands
                                ? uaData.brands.map(b => b.brand + ' ' + b.version).join(', ')
                                : '(缺失-异常)',
                            'cdc_残留': Object.keys(window).filter(k => k.startsWith('cdc_')).join(',') || '(无)',
                            'url': location.href
                        };
                    }"""));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("platform", platform);
            result.put("fingerprint", raw);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 诊断：在指定平台的会话里新开一个受控标签页，对比两个地址的加载表现。
     */
    @GetMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(
            @RequestParam(value = "platform", defaultValue = "boss") String platform,
            @RequestParam(defaultValue = "https://www.zhipin.com/web/geek/jobs?query=Java&city=101020100") String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        String probeJs = """
                () => ({
                  readyState: document.readyState,
                  title: document.title,
                  url: location.href,
                  bodyLength: document.body ? document.body.innerHTML.length : 0,
                  jobCards: document.querySelectorAll('li.job-card-box, li.job-card-wrapper').length,
                  listContainers: document.querySelectorAll('ul.rec-job-list, ul.job-list-box, .job-list-box').length,
                  pending: performance.getEntriesByType('resource')
                             .filter(r => r.responseEnd === 0).map(r => r.name).slice(0, 8)
                })""";
        String[] targets = {"https://www.zhipin.com", url};
        try {
            playwrightManager.ensureReady(platform);
            playwrightManager.callOn(platform, page -> {
                for (String target : targets) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    Page probe = null;
                    long start = System.currentTimeMillis();
                    try {
                        probe = page.context().newPage();
                        probe.setDefaultTimeout(20_000);
                        probe.navigate(target, new Page.NavigateOptions()
                                .setTimeout(20_000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                        result.put("navigateMs", System.currentTimeMillis() - start);
                        probe.waitForTimeout(5_000);
                        result.put("currentUrl", probe.url());
                        result.put("probe", probe.evaluate(probeJs));
                    } catch (Exception e) {
                        result.put("elapsedMs", System.currentTimeMillis() - start);
                        result.put("error", e.getMessage() == null ? e.toString()
                                : e.getMessage().split("\n")[0]);
                    } finally {
                        if (probe != null) {
                            try {
                                probe.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    out.put(target, result);
                }
                return null;
            });
            out.put("success", true);
            out.put("platform", platform);
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** 测试导航：同样在所属平台的线程上执行 */
    @GetMapping("/test-navigate")
    public ResponseEntity<Map<String, String>> testNavigate(
            @RequestParam(value = "platform", defaultValue = "boss") String platform) {
        try {
            playwrightManager.ensureReady(platform);
            Map<String, String> result = playwrightManager.callOn(platform, page -> {
                page.navigate("https://www.zhipin.com", new Page.NavigateOptions()
                        .setTimeout(60_000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                Map<String, String> data = new HashMap<>();
                data.put("success", "true");
                data.put("title", page.title());
                data.put("url", page.url());
                return data;
            });
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("success", "false");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
