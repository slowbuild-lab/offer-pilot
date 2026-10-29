package com.getjobs.application.controller;

import com.getjobs.application.service.CandidateScreeningService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CandidateScreeningControllerTest {
    @Test void rejectsForeignOriginsAndExposesUnavailableReadsAsErrors() throws Exception {
        var service=mock(CandidateScreeningService.class);
        when(service.records(1,null,null,null)).thenReturn(Map.of("items",List.of(),"total",0));
        when(service.records(0,null,null,null)).thenThrow(new IllegalArgumentException("页码错误"));
        when(service.records(2,null,null,null)).thenThrow(new IllegalStateException("筛选记录读取失败"));
        var mvc=MockMvcBuilders.standaloneSetup(new CandidateScreeningController(service)).build();
        mvc.perform(get("/api/delivery/screenings").header("Origin","https://foreign.invalid")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        mvc.perform(get("/api/delivery/screenings")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/delivery/screenings").param("page","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/delivery/screenings").param("page","2")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("筛选记录读取失败"));
        mvc.perform(get("/api/delivery/screenings").param("page","abc")).andExpect(status().isBadRequest());
    }
}
