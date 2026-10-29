package com.getjobs.application.controller;

import com.getjobs.application.service.BossDeliveryLedger;
import com.getjobs.application.service.DeliveryReadService;
import com.getjobs.application.service.ZhilianDeliveryLedger;
import com.getjobs.worker.manager.PlatformTaskGate;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.PlatformTaskCoordinator;
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
    private final BossDeliveryLedger boss;
    private final DeliveryReadService reads;

    public WorkbenchController(PlatformTaskCoordinator coordinator,
                               PlatformTaskGate gate,
                               PlaywrightManager playwrightManager,
                               ZhilianDeliveryLedger ledger,
                               BossDeliveryLedger boss, DeliveryReadService reads) {
        this.coordinator = coordinator;
        this.gate = gate;
        this.playwrightManager = playwrightManager;
        this.ledger = ledger;
        this.boss = boss;
        this.reads = reads;
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

        Map<String, Map<String,Object>> delivery = deliverySummaries();
        response.put("delivery", delivery);
        Map<String,Object> today=new LinkedHashMap<>();
        var z=delivery.get("zhilian");var b=delivery.get("boss");
        today.put("zhilianAttempts",z.get("todayAttempts"));
        today.put("zhilianRemaining",z.get("remainingToday"));
        today.put("zhilianDayLimit",z.get("effectiveLimit"));
        today.put("unknownCount",b.containsKey("error") || z.containsKey("error") ? null : asInt(b.get("unknownCount"))+asInt(z.get("unknownCount")));
        response.put("today",today);
        for(String platform:List.of("boss","zhilian")) {
            @SuppressWarnings("unchecked") var view=(Map<String,Object>)platforms.get(platform);
            var data=delivery.get(platform);
            for(String field:List.of("todayAttempts","remainingToday","unknownCount","reservedCount","effectiveLimit")) view.put(field,data.get(field));
            view.put("ledgerError",data.get("error"));
        }
        List<Map<String,Object>> attention=attentionItems(platforms,delivery);
        try { response.put("recent",reads.recentTasks()); }
        catch(Exception e) {
            response.put("recent",List.of());response.put("recentError","最近任务读取失败，请刷新重试");
            attention.add(item("system","RECORDS_UNAVAILABLE","任务历史读取失败","暂时无法读取任务历史，不能据此判断没有记录","refresh"));
        }
        response.put("attention",Map.of("count",attention.size(),"items",attention));
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
        List<Map<String, Object>> items = attentionItems(platforms,deliverySummaries());
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
        String phase=gate.phase(platform).name();
        view.put("taskState", "IDLE".equals(phase)?taskState:phase);
        view.put("isRunning", Boolean.TRUE.equals(source.get("isRunning")));
        view.put("isLoggedIn", playwrightManager.isLoggedIn(platform));
        view.put("browserReady", playwrightManager.isBrowserReady(platform));
        view.put("lastMessage", source.getOrDefault("lastMessage", ""));
        view.put("lastMessageType", source.getOrDefault("lastMessageType", "info"));
        view.put("pacing",source.get("pacing"));
        view.put("pacingLimit",source.get("pacingLimit"));
        view.put("pacingUsed",source.get("pacingUsed"));
        view.put("browserError", playwrightManager.lastBrowserError(platform));
        view.put("deliveredCount", asInt(source.get("deliveredCount")));
        // 只有做过并行验证的平台才标"已验证"，猎聘/前程无忧保持"未验证"，不夸大能力
        view.put("verified", platform.equals("boss") || platform.equals("zhilian"));
        view.put("todayAttempts", source.get("todayAttempts"));
        view.put("remainingToday", source.get("remainingToday"));
        view.put("unknownCount", source.get("unknownCount"));
        return view;
    }

    private Map<String,Map<String,Object>> deliverySummaries() {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(String platform:List.of("boss","zhilian")) {
            try { result.put(platform,platform.equals("boss")?boss.summary():ledger.summary(null,50)); }
            catch(Exception e) {
                log.warn("[{}] 工作台无法读取账本",platform);
                result.put(platform,Map.of("error","投递记录暂不可用，请检查本机服务与数据库"));
            }
        }
        return result;
    }

    private List<Map<String, Object>> attentionItems(Map<String,Object> platforms,Map<String,Map<String,Object>> delivery) {
        List<Map<String,Object>> items=new ArrayList<>();
        for(String platform:List.of("boss","zhilian")) {
            var data=delivery.get(platform);
            if(data.containsKey("error")) items.add(item(platform,"RECORDS_UNAVAILABLE","投递记录读取失败",String.valueOf(data.get("error")),"refresh"));
            else if(asInt(data.get("unknownCount"))>0) items.add(item(platform,"UNKNOWN_RESULT","结果待核实",data.get("unknownCount")+" 个岗位结果待核实，次数保留且不会自动重投","open_delivery_records"));
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
            String browserError=java.util.Objects.toString(view.get("browserError"), "");
            if (!Boolean.TRUE.equals(view.get("browserReady")) && !browserError.isBlank()) {
                item=item(platform,"BROWSER_ERROR","平台浏览器未能打开","请重新打开该平台浏览器；若再次退出，请从本机终端启动项目并检查权限","login");
            } else if ("PAUSED".equals(state)) {
                item = item(platform, "PAUSED", "任务已暂停", message.isBlank() ? "需要人工处理后继续" : message, "zhilian".equals(platform)?"resume":"open_platform");
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

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
