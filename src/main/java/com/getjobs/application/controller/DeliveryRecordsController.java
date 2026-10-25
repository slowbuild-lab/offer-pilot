package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.getjobs.application.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryRecordsController {
    private final BossDeliveryLedger boss;
    private final ZhilianDeliveryLedger zhilian;
    private final DailyQuotaService quota;
    @ModelAttribute public void localRead(@RequestHeader(value="Origin",required=false) String origin) { localMutation(origin); }
    @GetMapping("/summary") public Map<String,Object> summary() {
        var z=new LinkedHashMap<String,Object>(quota.get("zhilian"));
        z.putAll(zhilian.summary(null,50));
        return Map.of("boss",boss.summary(),"zhilian",z);
    }
    @GetMapping("/records") public Map<String,Object> records(@RequestParam(defaultValue="boss") String platform,
                                                               @RequestParam(defaultValue="1") int page) {
        DailyQuotaService.validatePlatform(platform);
        if (page<1 || page>10000) throw new IllegalArgumentException("页码必须为 1 到 10000");
        return Map.of("items","boss".equals(platform)?boss.attempts(page):zhilian.attempts(page),"page",page);
    }
    @PutMapping("/limits/{platform}") public Map<String,Object> limit(@PathVariable String platform,@RequestBody JsonNode body,
                                                        @RequestHeader(value="Origin",required=false) String origin) {
        localMutation(origin); DailyQuotaService.validatePlatform(platform);
        if (!body.has("limit") || !body.get("limit").isIntegralNumber() || !body.get("limit").canConvertToInt()) throw new IllegalArgumentException("每日上限必须为 1 到 500 的整数");
        return quota.update(platform,body.get("limit").intValue());
    }
    @PostMapping("/boss/records/{id}/resolve") public Map<String,Object> resolve(@PathVariable String id,@RequestBody JsonNode body,
                                                        @RequestHeader(value="Origin",required=false) String origin) {
        localMutation(origin);
        if (!body.has("completed") || !body.get("completed").isBoolean()) throw new IllegalArgumentException("请选择核实结果");
        boss.resolve(id,body.get("completed").booleanValue());
        return Map.of("success",true,"message","核实结果已保存，次数保留且不自动重投");
    }
    /** Local web origins only; no relaxation of the project's existing CORS configuration. */
    public static void localMutation(String origin) {
        if (origin==null) return;
        try {
            URI uri=URI.create(origin);
            if ("http".equals(uri.getScheme()) && uri.getHost()!=null && Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()) && uri.getRawUserInfo()==null) return;
        } catch (IllegalArgumentException ignored) { }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅允许本机管理页面修改");
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));
    }
}
