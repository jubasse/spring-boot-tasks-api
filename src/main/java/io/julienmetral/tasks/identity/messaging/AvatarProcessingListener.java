package io.julienmetral.tasks.identity.messaging;

import io.julienmetral.tasks.identity.services.AvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AvatarProcessingListener {

    private final AvatarService avatarService;

    @RabbitListener(queues = AvatarQueues.PROCESS)
    void process(AvatarUploaded event) {
        avatarService.process(event.userId(), event.uploadId());
    }
}
