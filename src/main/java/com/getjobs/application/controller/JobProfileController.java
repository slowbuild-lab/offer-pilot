package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.*;
import com.getjobs.application.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/job-profile")
@RequiredArgsConstructor
public class JobProfileController {
    private final JobProfileService profiles;
    private final JobProfileRuntime runtime;
    private final JobPreviewCollector collector;
    private final BossService boss;
    private final ZhilianService zhilian;
    private final ObjectMapper json=new ObjectMapper();
    @ModelAttribute public void localRead(@RequestHeader(value="Origin",required=false) String origin) { DeliveryRecordsController.localMutation(origin); }
    @GetMapping public Map<String,Object> state() {
        var state=profiles.state();
        var draft=(JobProfileService.Snapshot)state.get("draft");
        state.put("preview",draft==null?List.of():profiles.preview(draft.version())); return state;
    }
    @GetMapping("/cities") public List<String> cities() {
        var z=zhilian.getOptionsByType("city").stream().map(o -> JobProfile.cityName(o.getName())).collect(java.util.stream.Collectors.toSet());
        return boss.getOptionsByType("city").stream().map(o -> o.getName()).filter(n -> z.contains(JobProfile.cityName(n))).distinct().sorted().toList();
    }
    @PostMapping("/versions") public Map<String,Object> save(@RequestBody JsonNode body,@RequestHeader(value="Origin",required=false) String origin) throws Exception {
        DeliveryRecordsController.localMutation(origin);
        if (!body.isObject()) throw new IllegalArgumentException("方案必须为对象");
        var allowed=Set.of("name","platforms","keywords","city","minimumMonthlySalary","salaryHard","excludeCompanies","excludeJobs","bossMessage","bossImage","zhilianResumeConfirmed","bossFilters","contactPolicy");
        var fields=body.fieldNames(); while(fields.hasNext()) if (!allowed.contains(fields.next())) throw new IllegalArgumentException("包含未支持的方案字段");
        for(String field:List.of("salaryHard","bossImage","zhilianResumeConfirmed")) if (body.has(field) && !body.get(field).isBoolean()) throw new IllegalArgumentException(field+" 必须为布尔值");
        if (body.hasNonNull("minimumMonthlySalary") && (!body.get("minimumMonthlySalary").isIntegralNumber() || !body.get("minimumMonthlySalary").canConvertToInt())) throw new IllegalArgumentException("最低月薪必须为整数");
        for(String field:List.of("name","city","bossMessage")) if (body.has(field) && !body.get(field).isTextual()) throw new IllegalArgumentException(field+" 必须为文本");
        for(String field:List.of("platforms","keywords","excludeCompanies","excludeJobs")) if (body.has(field)) {
            if (!body.get(field).isArray()) throw new IllegalArgumentException(field+" 必须为列表");
            for (JsonNode item:body.get(field)) if (!item.isTextual()) throw new IllegalArgumentException(field+" 必须为文本列表");
        }
        if (body.has("bossFilters")) {
            var filters=body.get("bossFilters");
            if (!filters.isObject()) throw new IllegalArgumentException("BOSS高级条件必须为对象");
            var supported=Set.of("includeCompanies","descriptionInclude","descriptionExclude");
            var names=filters.fieldNames();
            while(names.hasNext()) {
                String field=names.next();
                if (!supported.contains(field)) throw new IllegalArgumentException("包含未支持的BOSS高级条件");
                if (!filters.get(field).isArray()) throw new IllegalArgumentException("BOSS高级条件必须为文本列表");
                for(JsonNode item:filters.get(field)) if (!item.isTextual()) throw new IllegalArgumentException("BOSS高级条件必须为文本列表");
            }
        }
        if (body.has("contactPolicy")) {
            var policy=body.get("contactPolicy");
            if (!policy.isObject()) throw new IllegalArgumentException("BOSS HR 冷却期必须为对象");
            var names=policy.fieldNames();
            while(names.hasNext()) if (!"hrCooldownDays".equals(names.next())) throw new IllegalArgumentException("包含未支持的冷却期字段");
            if (policy.hasNonNull("hrCooldownDays") && (!policy.get("hrCooldownDays").isIntegralNumber() || !policy.get("hrCooldownDays").canConvertToInt()))
                throw new IllegalArgumentException("HR 冷却期天数必须为整数");
        }
        try { return Map.of("version",profiles.save(json.treeToValue(body,JobProfile.class))); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("方案字段不合法，请检查名称、城市和关键词"); }
    }
    @PostMapping("/{version}/preview") public Map<String,Object> preview(@PathVariable long version,@RequestHeader(value="Origin",required=false) String origin) {
        DeliveryRecordsController.localMutation(origin);
        profiles.invalidatePreview(version);
        var snapshot=profiles.get(version);
        String token=runtime.materialToken(snapshot.rules());
        var candidates=collector.collect(snapshot);
        return Map.of("items",profiles.recordPreview(version,candidates,token),"version",version);
    }
    @PostMapping("/{version}/confirm") public Map<String,Object> confirm(@PathVariable long version,@RequestHeader(value="Origin",required=false) String origin) {
        DeliveryRecordsController.localMutation(origin);
        var snapshot=profiles.get(version);
        if (!runtime.materialToken(snapshot.rules()).equals(snapshot.materialToken())) throw new IllegalStateException("素材已变化，请重新预览确认");
        profiles.confirm(version);
        return Map.of("success",true,"message","方案已确认，将用于所选平台下一次新任务；尚未开启自动计划");
    }
    @PostMapping("/forbidden") public Map<String,Object> forbid(@RequestBody JsonNode body,@RequestHeader(value="Origin",required=false) String origin) {
        DeliveryRecordsController.localMutation(origin);
        if (!body.path("kind").isTextual() || !body.path("value").isTextual()) throw new IllegalArgumentException("请选择明确的禁止目标");
        profiles.forbid(body.get("kind").textValue(),body.get("value").textValue());
        return Map.of("success",true,"message","已加入紧急禁止，将在下一次外发前检查");
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("message",e.getMessage())); }
    @ExceptionHandler(com.getjobs.worker.manager.PlatformTaskGate.Rejected.class) public ResponseEntity<?> busy(RuntimeException e) { return ResponseEntity.status(409).body(Map.of("message","平台正在使用中，请先停止该平台任务再预览")); }
}
