package io.julienmetral.tasks.export.batch;

import io.julienmetral.tasks.export.services.DataExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Renews a running export's lease as each step starts and after each chunk. Set once when the run started, the lease
 * of a run longer than {@code exports.lease} ran out: the recovery job queued it again, and a second instance ran it
 * while the first still did.
 */
@Component
@RequiredArgsConstructor
class DataExportLeaseRenewal implements StepExecutionListener, ChunkListener<Object, Object> {

    private final DataExportService exportService;

    @Override
    public void beforeStep(StepExecution step) {
        renew(step);
    }

    @Override
    public void afterChunk(Chunk<Object> chunk) {
        StepContext context = StepSynchronizationManager.getContext();

        if (context != null) {
            renew(context.getStepExecution());
        }
    }

    private void renew(StepExecution step) {
        exportService.renewLease(UUID.fromString(step.getJobParameters().getString(PublishExport.EXPORT_ID)));
    }
}
