package com.getjobs.application.service;

/** Read-only platform facts plus the original platform configuration's prefilter result. */
public record JobCandidate(String platform, String jobId, String title, String company, String city,
                           String salary, String link, String description, String platformFilterReason,
                           String hrId, String hrActiveRaw) {
    public JobCandidate(String platform,String jobId,String title,String company,String city,String salary,String link) {
        this(platform,jobId,title,company,city,salary,link,null,null,null,null);
    }
    public JobCandidate(String platform,String jobId,String title,String company,String city,String salary,String link,String description) {
        this(platform,jobId,title,company,city,salary,link,description,null,null,null);
    }
    public JobCandidate(String platform,String jobId,String title,String company,String city,String salary,String link,
                        String description,String platformFilterReason) {
        this(platform,jobId,title,company,city,salary,link,description,platformFilterReason,null,null);
    }
    /** Attaches the recruiter facts used by the contact policy; the rest of the candidate stays untouched. */
    public JobCandidate withHr(String hrId,String hrActiveRaw) {
        return new JobCandidate(platform,jobId,title,company,city,salary,link,description,platformFilterReason,hrId,hrActiveRaw);
    }
}
