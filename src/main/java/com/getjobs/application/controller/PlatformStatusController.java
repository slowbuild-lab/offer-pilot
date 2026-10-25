package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.PlatformPreflightService;
import com.getjobs.worker.service.PlatformTaskCoordinator;
import com.getjobs.worker.service.StartOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多平台运行状态汇总。
 *
 * <p>只读接口，给前端展示"每个平台自己的状态"和"当前并行几个平台、上限多少"。
 * 之前前端只能看到单个平台，无法判断另一个平台是不是真的在跑。</p>
 */
@RestController
@RequestMapping("/api/platforms")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class PlatformStatusController {

    private final PlatformTaskCoordinator coordinator;
    private final PlaywrightManager playwrightManager;
    private final PlatformPreflightService preflightService;

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> parallel = coordinator.gate().snapshot();
        response.put("success", true);
        response.putAll(parallel);
        response.put("platforms", coordinator.statuses());
        response.put("browsers", playwrightManager.browserStatus());
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }

    /** 打开指定平台的独立浏览器窗口用于扫码登录（异步，不阻塞接口） */
    @PostMapping("/{platform}/login")
    public ResponseEntity<Map<String, Object>> login(@PathVariable String platform) {
        if (!playwrightManager.platforms().contains(platform)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "不支持的平台: " + platform));
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                playwrightManager.openLogin(platform);
            } catch (Exception e) {
                // 失败原因会写进会话的 lastError，由 /api/workbench/summary 带回界面
                org.slf4j.LoggerFactory.getLogger(PlatformStatusController.class)
                        .warn("[{}] 打开浏览器失败: {}", platform, e.getMessage());
            }
        });
        return ResponseEntity.ok(Map.of(
                "success", true,
                "status", "opening",
                "message", "正在打开该平台独立浏览器窗口（首次启动约需 10-30 秒），请在窗口内扫码登录"
        ));
    }

    /** 强制结束：只关闭该平台自己的浏览器，其它平台继续运行 */
    @PostMapping("/{platform}/force-stop")
    public ResponseEntity<Map<String, Object>> forceStop(@PathVariable String platform) {
        if (!playwrightManager.platforms().contains(platform)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "不支持的平台: " + platform));
        }
        StartOutcome outcome = coordinator.forceStop(platform);
        return ResponseEntity.ok(Map.of(
                "success", outcome.accepted(),
                "status", outcome.status(),
                "message", outcome.message()
        ));
    }

    /**
     * 运行前预检：只读，不启动浏览器、不改任务状态。
     * WARN 表示可以继续但需要提示，只有 FAIL 才应阻止启动。
     */
    @PostMapping("/{platform}/preflight")
    public ResponseEntity<Map<String, Object>> preflight(@PathVariable String platform) {
        if (!playwrightManager.platforms().contains(platform)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "不支持的平台: " + platform));
        }
        Map<String, Object> result = preflightService.check(platform);
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    /** 全部停止：只对正在占用的平台发停止指令，返回每个平台的结果 */
    @PostMapping("/stop-all")
    public ResponseEntity<Map<String, Object>> stopAll() {
        Map<String, Object> results = new LinkedHashMap<>();
        for (String platform : coordinator.gate().activePlatforms()) {
            StartOutcome outcome = coordinator.stop(platform);
            results.put(platform, Map.of(
                    "accepted", outcome.accepted(),
                    "status", outcome.status(),
                    "message", outcome.message()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "stopped", results,
                "count", results.size(),
                "message", results.isEmpty() ? "当前没有正在运行的平台任务" : "已向 " + results.size() + " 个平台发送停止请求"
        ));
    }
}
