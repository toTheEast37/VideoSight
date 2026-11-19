package com.vibeteaching.parsing.mq;

import com.vibeteaching.parsing.ParseModels.WorkMessage;

@FunctionalInterface
public interface ParseMessagePublisher {
    void send(WorkMessage message);
}
