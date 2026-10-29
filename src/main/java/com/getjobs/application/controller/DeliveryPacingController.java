package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.*;
import com.getjobs.application.service.DeliveryPacingService;
import com.getjobs.worker.manager.PlatformTaskGate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/delivery/pacing")
public class DeliveryPacingController {
    private final DeliveryPacingService service;
    private final PlatformTaskGate gate;
    private final ObjectMapper json=new ObjectMapper();
    public DeliveryPacingController(DeliveryPacingService service,PlatformTaskGate gate){this.service=service;this.gate=gate;}
    @ModelAttribute public void local(@RequestHeader(value="Origin",required=false)String origin){DeliveryRecordsController.localMutation(origin);}
    @GetMapping public Map<String,Object> settings(){return service.snapshot();}
    @GetMapping("/records") public Map<String,Object> records(@RequestParam(required=false)String platform,@RequestParam(defaultValue="1")int page){return service.records(platform,page);}
    @PutMapping("/policies/{platform}") public Map<String,Object> update(@PathVariable String platform,@RequestBody JsonNode body){
        fields(body,Set.of("version","policy"));
        if(!body.path("version").isIntegralNumber()||!body.path("version").canConvertToLong()||body.path("version").asLong()<1)throw new IllegalArgumentException("缺少有效配置版本，请刷新");
        JsonNode policy=body.path("policy");
        Class<?> type="global".equals(platform)?DeliveryPacingService.GlobalPolicy.class:DeliveryPacingService.Policy.class;
        Set<String> keys=new HashSet<>();for(var component:type.getRecordComponents())keys.add(component.getName());fields(policy,keys);
        for(String key:keys)if(!policy.path(key).isIntegralNumber()||!policy.path(key).canConvertToInt())throw new IllegalArgumentException("节奏参数必须为整数："+key);
        try{
            if("global".equals(platform))service.updateGlobal(body.path("version").asLong(),json.treeToValue(policy,DeliveryPacingService.GlobalPolicy.class));
            else service.update(platform,body.path("version").asLong(),json.treeToValue(policy,DeliveryPacingService.Policy.class));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("节奏参数超出范围，请检查输入");}
        return service.snapshot();
    }
    @PostMapping("/{platform}/resume") public Map<String,Object> resume(@PathVariable String platform,@RequestBody JsonNode body){
        DeliveryPacingService.platform(platform);fields(body,Set.of("acknowledged"));
        if(!body.path("acknowledged").isBoolean()||!body.path("acknowledged").asBoolean())throw new IllegalArgumentException("请先核实平台页面及待确认投递结果");
        if(gate.holding(platform))throw new IllegalStateException("请先停止该平台任务，等待退出后恢复保护状态");
        service.resume(platform);return service.snapshot();
    }
    private static void fields(JsonNode node,Set<String> allowed){
        if(!node.isObject())throw new IllegalArgumentException("请提交完整的节奏配置");
        var it=node.fieldNames();while(it.hasNext())if(!allowed.contains(it.next()))throw new IllegalArgumentException("包含未知配置字段");
        if(node.size()!=allowed.size())throw new IllegalArgumentException("缺少配置字段，请刷新后重试");
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
}
