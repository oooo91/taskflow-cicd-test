package com.domain.taskflow.reaper;

import com.domain.taskflow.domain.Job;
import com.domain.taskflow.domain.JobEvent;
import com.domain.taskflow.heartbeat.HeartbeatService;
import com.domain.taskflow.repo.JobEventRepository;
import com.domain.taskflow.repo.JobRepository;
import com.domain.taskflow.retry.RetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StaleJobReaper {

    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;
    private final HeartbeatService heartbeatService;
    private final RetryPolicy retryPolicy;
    private final ReaperProperties reaperProperties;

    @Scheduled(fixedDelayString = "${taskflow.reaper.interval-ms:1000}")
    @Transactional
    public void tick() {
        OffsetDateTime now = OffsetDateTime.now();
        long staleRunningMs = reaperProperties.getStaleRunningMs();
        OffsetDateTime cutoff = now.minusNanos(staleRunningMs * 1_000_000);

        List<Job> candidates = jobRepository.findRunningOlderThan(cutoff);
        for (Job job : candidates) {
            String workerId = job.getWorkerId();
            if (workerId == null || workerId.isBlank()) continue;

            // worker heartbeat 살아있으면 stale 아님
            if (heartbeatService.isAlive(workerId)) continue;

            // stale 이면 회수
            handleStale(job, now);
        }
    }

    private void handleStale(Job job, OffsetDateTime now) {
        // stale 회수 사유
        String code = "STALE_WORKER";
        String message = "Worker heartbeat missing: workerId=" + job.getWorkerId();

        // stale 은 인프라 장애로 보고 retryable=true로 두기
        boolean retryable = retryPolicy.isRetryable("EXEC_ERROR");
        boolean hasMoreAttempts = job.getAttemptCount() < job.getMaxAttempts();

        if (retryable && hasMoreAttempts) {
            // 즉시 재시도 큐로 (바로 pending 하지 않고 정책상 retry_wait 후 -> scheduler 로 통일)
            OffsetDateTime nextRunAt = now;
            job.markRetryWait(nextRunAt, code, message); // runningStartedAt 초기화 + attemptCount++

            jobEventRepository.save(new JobEvent(
                    UUID.randomUUID(),
                    job.getId(),
                    "STATUS_CHANGED",
                    "{\"jobId\":\"" + job.getId() + "\",\"to\":\"RETRY_WAIT\",\"reason\":\"STALE\",\"errorCode\":\"" + code + "\"}"
            ));
        } else {
            job.markFailedFinal(code, message); // runningStartedAt=null + attemptCount++

            jobEventRepository.save(new JobEvent(
                    UUID.randomUUID(),
                    job.getId(),
                    "STATUS_CHANGED",
                    "{\"jobId\":\"" + job.getId() + "\",\"to\":\"FAILED\",\"reason\":\"STALE\",\"errorCode\":\"" + code + "\"}"
            ));
        }
    }
}
