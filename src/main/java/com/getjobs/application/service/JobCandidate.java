package com.getjobs.application.service;

/** Read-only facts from a platform. Missing facts remain missing rather than inferred. */
public record JobCandidate(String platform, String jobId, String title, String company, String city, String salary, String link) {}
