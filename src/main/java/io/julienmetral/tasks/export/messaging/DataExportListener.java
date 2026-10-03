package io.julienmetral.tasks.export.messaging;

import io.julienmetral.tasks.export.services.DataExportRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DataExportListener {

    private final DataExportRunner runner;

    @RabbitListener(queues = ExportQueues.RUN, containerFactory = ExportQueues.LISTENER_FACTORY)
    void run(DataExportRequested request) {
        runner.run(request.exportId());
    }
}
