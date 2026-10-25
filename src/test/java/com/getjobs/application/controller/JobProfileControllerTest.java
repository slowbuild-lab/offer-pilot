package com.getjobs.application.controller;

import com.getjobs.application.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobProfileControllerTest {
    @Test void rejectsNullObjectsCoercedFieldsAndUnimplementedHardConditions() throws Exception {
        var profiles=mock(JobProfileService.class);
        var controller=new JobProfileController(profiles,mock(JobProfileRuntime.class),mock(JobPreviewCollector.class),mock(BossService.class),mock(ZhilianService.class));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        for(String input:new String[]{"null","[]","{\"salaryHard\":\"true\"}","{\"minimumMonthlySalary\":1.5}","{\"experience\":3}","{\"keywords\":[1]}"})
            mvc.perform(post("/api/job-profile/versions").contentType("application/json").content(input)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/job-profile").header("Origin","https://foreign.invalid")).andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }
}
