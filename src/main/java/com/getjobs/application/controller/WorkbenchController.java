package com.getjobs.application.controller;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.ZhilianDeliveryLedger;
import com.getjobs.worker.manager.PlatformTaskGate;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.PlatformTaskCoordinator;
import com.getjobs.worker.zhilian.ZhilianConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作战台聚合接口：一次请求给出并行概况、各平台状态、今日数字、需要处理的事项和最近任务。
 *
 * <p>全部只读，不启动浏览器、不改任务状态。前端不再为了首页拼 4 个接口，
 * 也不会因为某个平台接口慢而让整页看起来"没数据"。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/workbench")
@CrossOrigin(origins = "*")
public class WorkbenchController {

    private static final List<String> PLATFORM_ORDER = List.of("boss", "zhilian", "liepin", "51job");

    private final PlatformTaskCoordinator coordinator;
    private final PlatformTaskGate gate;
    private final PlaywrightManager playwrightManager;
    private final ZhilianDeliveryLedger ledger;
    private final ConfigService configService;

    public WorkbenchController(PlatformTaskCoordinator coordinator,
                               PlatformTaskGate gate,
                               PlaywrightManager playwrightManager,
                               ZhilianDeliveryLedger ledger,
                               ConfigService configService) {
        this.coordinator = coordinator;
        this.gate = gate;
        this.playwrightManager = playwrightManager;
        this.ledger = ledger;
        this.configService = configService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);

        Map<String, Object> parallel = gate.snapshot();
        response.put("parallel", parallel);

        Map<String, Map<String, Object>> statuses = coordinator.statuses();
        Map<String, Object> platforms = new LinkedHashMap<>();
        for (String platform : PLATFORM_ORDER) {
            platforms.put(platform, platformView(platform, statuses.get(platform)));
        }
        response.put("platforms", platforms);

        Map<String, Object> today = new LinkedHashMap<>();
        int unknownCount = 0;
        try {
            Map<String, Object> ledgerSummary = ledger.summary(null, 50);
            int attempts = asInt(ledgerSummary.get("todayAttempts"));
            int dayLimit = asInt(ledgerSummary.get("effectiveLimit"));
            today.put("zhilianAttempts", attempts);
            today.put("zhilianRemaining", asInt(ledgerSummary.get("remainingToday")));
            today.put("zhilianDayLimit", dayLimit);
            unknownCount = asInt(ledgerSummary.get("unknownCount"));
        } catch (Exception e) {
            log.warn("读取智联投递记录失败: {}", e.getMessage());
            today.put("zhilianAttempts", null);
            today.put("zhilianRemaining", null);
            today.put("error", "投递记录读取失败");
        }
        today.put("unknownCount", unknownCount);
        response.put("today", today);

        List<Map<String, Object>> attention = attentionItems(platforms);
        response.put("attention", Map.of("count", attention.size(), "items", attention));

        List<Map<String, Object>> recent = new ArrayList<>();
        try {
            recent = ledger.recentTasks(8);
        } catch (Exception e) {
            log.warn("读取最近任务失败: {}", e.getMessage());
        }
        response.put("recent", recent);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }

    @GetMapping("/attention")
    public Map<String, Object> attention() {
        Map<String, Map<String, Object>> statuses = coordinator.statuses();
        Map<String, Object> platforms = new LinkedHashMap<>();
        for (String platform : PLATFORM_ORDER) {
            platforms.put(platform, platformView(platform, statuses.get(platform)));
        }
        List<Map<String, Object>> items = attentionItems(platforms);
        return Map.of("success", true, "count", items.size(), "items", items);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> platformView(String platform, Map<String, Object> status) {
        Map<String, Object> source = status == null ? Map.of() : status;
        String taskState = String.valueOf(source.getOrDefault("taskState", ""));
        if (taskState.isBlank() || "null".equals(taskState)) {
            taskState = Boolean.TRUE.equals(source.get("isRunning")) ? "RUNNING" : "IDLE";
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("platform", platform);
        view.put("taskState", taskState);
        view.put("isRunning", Boolean.TRUE.equals(source.get("isRunning")));
        view.put("isLoggedIn", playwrightManager.isLoggedIn(platform));
        view.put("browserReady", playwrightManager.isBrowserReady(platform));
        view.put("lastMessage", source.getOrDefault("lastMessage", ""));
        view.put("lastMessageType", source.getOrDefault("lastMessageType", "info"));
        view.put("browserError", playwrightManager.lastBrowserError(platform));
        view.put("deliveredCount", asInt(source.get("deliveredCount")));
        // 只有做过并行验证的平台才标"已验证"，猎聘/前程无忧保持"未验证"，不夸大能力
        view.put("verified", platform.equals("boss") || platform.equals("zhilian"));
        view.put("todayAttempts", source.get("todayAttempts"));
        view.put("remainingToday", source.get("remainingToday"));
        view.put("unknownCount", source.get("unknownCount"));
        return view;
    }

    private List<Map<String, Object>> attentionItems(Map<String, Object> platforms) {
        List<Map<String, Object>> items = new ArrayList<>();

        // 1. 智联待核实的投递结果
        try {
            for (Map<String, Object> row : ledger.unknownAttempts()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("platform", "zhilian");
                item.put("kind", "UNKNOWN_RESULT");
                item.put("ref", row.get("id"));
                item.put("title", "投递结果待核实");
                item.put("message", joinJob(row.get("jobTitle"), row.get("company"), row.get("reason")));
                item.put("action", "open_zhilian_attempts");
                items.add(item);
            }
        } catch (Exception e) {
            log.warn("读取待核实记录失败: {}", e.getMessage());
        }

        // 2. 暂停 / 登录失效 / 任务异常：每个平台最多一条，按严重程度取最高
        for (String platform : PLATFORM_ORDER) {
            @SuppressWarnings("unchecked")
            Map<String, Object> view = (Map<String, Object>) platforms.get(platform);
            if (view == null) {
                continue;
            }
            String state = String.valueOf(view.get("taskState"));
            String message = String.valueOf(view.getOrDefault("lastMessage", ""));
            String type = String.valueOf(view.getOrDefault("lastMessageType", "info"));
            boolean loggedIn = Boolean.TRUE.equals(view.get("isLoggedIn"));

            Map<String, Object> item = null;
            if ("PAUSED".equals(state)) {
                item = item(platform, "PAUSED", "任务已暂停", message.isBlank() ? "需要人工处理后继续" : message, "resume");
            } else if (!loggedIn && !message.isBlank() && ("error".equals(type) || "warning".equals(type))) {
                item = item(platform, "LOGIN_REQUIRED", "登录已失效", message, "login");
            } else if ("error".equals(type) && !message.isBlank() && !"RUNNING".equals(state)) {
                item = item(platform, "TASK_ERROR", "上次任务失败", message, "open_platform");
            }
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> item(String platform, String kind, String title, String message, String action) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("platform", platform);
        item.put("kind", kind);
        item.put("title", title);
        item.put("message", message);
        item.put("action", action);
        return item;
    }

    private String joinJob(Object title, Object company, Object reason) {
        StringBuilder builder = new StringBuilder();
        if (title != null && !String.valueOf(title).isBlank()) {
            builder.append(title);
        }
        if (company != null && !String.valueOf(company).isBlank()) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(company);
        }
        if (reason != null && !String.valueOf(reason).isBlank()) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(reason);
        }
        return builder.length() == 0 ? "结果未确认，请人工核实" : builder.toString();
    }

    private int zhilianDayLimit() {
        try {
            ZhilianConfig config = configService.getZhilianConfig();
            return config.getMaxAttemptsPerDay();
        } catch (Exception e) {
            return 50;
        }
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
