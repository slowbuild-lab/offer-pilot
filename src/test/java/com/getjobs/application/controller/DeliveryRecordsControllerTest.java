package com.getjobs.application.controller;

import com.getjobs.application.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeliveryRecordsControllerTest {
    @Test void rejectsNonIntegerLimitsUnsupportedPlatformsAndForeignMutationOrigin() throws Exception {
        var boss=mock(BossDeliveryLedger.class); var z=mock(ZhilianDeliveryLedger.class); var quota=mock(DailyQuotaService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new DeliveryRecordsController(boss,z,quota)).build();
        mvc.perform(put("/api/delivery/limits/boss").contentType("application/json").content("{\"limit\":1.5}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/delivery/limits/boss").header("Origin","https://foreign.invalid").contentType("application/json").content("{\"limit\":20}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/delivery/records").param("platform","unsupported")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/delivery/records").param("page","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/delivery/summary").header("Origin","https://foreign.invalid")).andExpect(status().isForbidden());
        verifyNoInteractions(quota);
    }
}
