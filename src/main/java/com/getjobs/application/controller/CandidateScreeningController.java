package com.getjobs.application.controller;

import com.getjobs.application.service.CandidateScreeningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/delivery/screenings")
@RequiredArgsConstructor
public class CandidateScreeningController {
    private final CandidateScreeningService screenings;
    @ModelAttribute public void localRead(@RequestHeader(value="Origin",required=false) String origin) { DeliveryRecordsController.localMutation(origin); }
    @GetMapping public Map<String,Object> records(@RequestParam(defaultValue="1") int page,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(required=false) String result) {
        return screenings.records(page,from,to,result);
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("message",e.getMessage())); }
}
