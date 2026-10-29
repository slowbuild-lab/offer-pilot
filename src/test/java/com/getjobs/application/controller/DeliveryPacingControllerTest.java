package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.DeliveryPacingService;
import com.getjobs.worker.manager.PlatformTaskGate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeliveryPacingControllerTest {
    @Test void policiesAreStrictVersionedAndLocalOnly() throws Exception {
        var service=mock(DeliveryPacingService.class);var gate=mock(PlatformTaskGate.class);
        var mvc=MockMvcBuilders.standaloneSetup(new DeliveryPacingController(service,gate)).build();
        mvc.perform(get("/api/delivery/pacing").header("Origin","https://foreign.invalid")).andExpect(status().isForbidden());verifyNoInteractions(service);
        when(service.snapshot()).thenReturn(Map.of("day","2026-10-28"));
        String valid=new ObjectMapper().writeValueAsString(Map.of("version",1,"policy",DeliveryPacingService.Policy.defaults()));
        mvc.perform(put("/api/delivery/pacing/policies/boss").contentType("application/json").content(valid)).andExpect(status().isOk());
        verify(service).update(eq("boss"),eq(1L),eq(DeliveryPacingService.Policy.defaults()));
        for(String invalid:new String[]{valid.replace("\"minIntervalSeconds\":120","\"minIntervalSeconds\":\"120\""),valid.replace("\"minIntervalSeconds\":120","\"minIntervalSeconds\":120.5"),valid.replace("\"version\":1","\"version\":0"),valid.replace("\"version\":1","\"version\":1,\"unknown\":true"),"{}"}){
            mvc.perform(put("/api/delivery/pacing/policies/boss").contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        doThrow(new IllegalStateException("版本已变化")).when(service).update(eq("boss"),eq(2L),any());
        mvc.perform(put("/api/delivery/pacing/policies/boss").contentType("application/json").content(valid.replace("\"version\":1","\"version\":2"))).andExpect(status().isConflict());
    }
    @Test void manualRecoveryNeedsAcknowledgementAndAnIdlePlatform() throws Exception {
        var service=mock(DeliveryPacingService.class);var gate=mock(PlatformTaskGate.class);
        var mvc=MockMvcBuilders.standaloneSetup(new DeliveryPacingController(service,gate)).build();
        mvc.perform(post("/api/delivery/pacing/boss/resume").contentType("application/json").content("{\"acknowledged\":false}")).andExpect(status().isBadRequest());
        when(gate.holding("boss")).thenReturn(true);
        mvc.perform(post("/api/delivery/pacing/boss/resume").contentType("application/json").content("{\"acknowledged\":true}")).andExpect(status().isConflict());
        verify(service,never()).resume(anyString());
        when(gate.holding("boss")).thenReturn(false);when(service.snapshot()).thenReturn(Map.of());
        mvc.perform(post("/api/delivery/pacing/boss/resume").contentType("application/json").content("{\"acknowledged\":true}")).andExpect(status().isOk());verify(service).resume("boss");
    }
}
