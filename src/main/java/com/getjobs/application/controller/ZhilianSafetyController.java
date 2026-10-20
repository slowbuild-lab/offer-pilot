package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.getjobs.application.service.ZhilianDeliveryLedger;
import com.getjobs.worker.service.ZhilianJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/zhilian")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ZhilianSafetyController {
    private final ZhilianDeliveryLedger ledger;
    private final ZhilianJobService tasks;

    @GetMapping("/attempts/unknown")
    public ResponseEntity<?> unknown(@RequestParam(defaultValue = "1") int page) {
        if (page < 1 || page > 10000) return error(400, "页码必须为1到10000的整数");
        return ResponseEntity.ok(Map.of("items", ledger.unknownAttempts(page), "page", page,
                "total", ledger.summary(null, 50).get("unknownCount")));
    }

    @PostMapping("/attempts/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable String id, @RequestBody JsonNode body) {
        if (!body.has("delivered") || !body.get("delivered").isBoolean()) return error(400, "请选择已投递或未成功");
        if (tasks.isRunning()) return error(409, "请先停止或暂停任务，再核实投递结果");
        try {
            ledger.resolve(id, body.get("delivered").booleanValue());
            return ResponseEntity.ok(Map.of("success", true, "message", "已登记核实结果，本次尝试次数保留"));
        } catch (IllegalArgumentException e) { return error(400, e.getMessage()); }
    }

    @PostMapping("/resume")
    public ResponseEntity<?> resume() {
        if (!tasks.resumeDelivery(message -> {})) return error(409, "当前没有可继续的暂停任务，请刷新状态后检查");
        return ResponseEntity.ok(Map.of("success", true, "message", "正在重新检查登录与额度，暂停任务将使用原配置继续"));
    }

    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "message", message));
    }
}
