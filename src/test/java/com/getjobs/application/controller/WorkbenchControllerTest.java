package com.getjobs.application.controller;

import com.getjobs.application.service.*;
import com.getjobs.worker.manager.*;
import com.getjobs.worker.service.PlatformTaskCoordinator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbenchControllerTest {
    @Test void missingLedgerDataNeverBecomesZeroUnknownsOrAnEmptySuccess() {
        var ledger=mock(ZhilianDeliveryLedger.class);
        when(ledger.summary(null,50)).thenThrow(new IllegalStateException("storage unavailable"));
        var coordinator=mock(PlatformTaskCoordinator.class);
        when(coordinator.statuses()).thenReturn(Map.of());
        var controller=new WorkbenchController(coordinator,new PlatformTaskGate(2),mock(PlaywrightManager.class),ledger,mock(BossDeliveryLedger.class),mock(DeliveryReadService.class));
        var summary=controller.summary();
        var today=(Map<?,?>)summary.get("today");
        assertNull(today.get("unknownCount"),"Unreadable results must remain unknown instead of looking like zero");
        var attention=(Map<?,?>)summary.get("attention");
        assertTrue(((Number)attention.get("count")).intValue()>0,"Storage failure must be actionable on the home page");
    }
    @Test void stoppingGateTakesPriorityOverAWorkersStaleRunningStatus() {
        var coordinator=mock(PlatformTaskCoordinator.class);
        when(coordinator.statuses()).thenReturn(Map.of("boss",Map.of("taskState","RUNNING","isRunning",true)));
        var gate=new PlatformTaskGate(2);var ticket=gate.acquire("boss");gate.markStopping(ticket);
        var controller=new WorkbenchController(coordinator,gate,mock(PlaywrightManager.class),mock(ZhilianDeliveryLedger.class),mock(BossDeliveryLedger.class),mock(DeliveryReadService.class));
        var platforms=(Map<?,?>)controller.summary().get("platforms");
        assertEquals("STOPPING",((Map<?,?>)platforms.get("boss")).get("taskState"));
    }
    @Test void includesBossUnknownsAndBothRecentTaskSources() {
        var z=mock(ZhilianDeliveryLedger.class);var boss=mock(BossDeliveryLedger.class);var reads=mock(DeliveryReadService.class);
        when(z.summary(null,50)).thenReturn(Map.of("unknownCount",2,"todayAttempts",4,"remainingToday",46,"effectiveLimit",50));
        when(boss.summary()).thenReturn(Map.of("unknownCount",3,"todayAttempts",5,"remainingToday",14,"reservedCount",1,"effectiveLimit",20));
        when(reads.recentTasks()).thenReturn(List.of(Map.of("platform","boss","id","b"),Map.of("platform","zhilian","id","z")));
        var coordinator=mock(PlatformTaskCoordinator.class);when(coordinator.statuses()).thenReturn(Map.of());
        var controller=new WorkbenchController(coordinator,new PlatformTaskGate(2),mock(PlaywrightManager.class),z,boss,reads);
        var result=controller.summary();assertEquals(5,((Map<?,?>)result.get("today")).get("unknownCount"));
        assertEquals(2,((List<?>)result.get("recent")).size());
        var platforms=(Map<?,?>)result.get("platforms");assertEquals(20,((Map<?,?>)platforms.get("boss")).get("effectiveLimit"));
        assertEquals(2,((Map<?,?>)result.get("attention")).get("count"));
    }
}
