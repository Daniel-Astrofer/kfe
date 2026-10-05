package com.kerosene.kfe.messaging.application;

import com.kerosene.kfe.messaging.envelope.MessageEnvelope;

/** Inbound message port; implementations own idempotent aggregate effects. */
public interface MessageHandler {
    boolean supports(String type, int schemaVersion);

    void handle(MessageEnvelope message);
}
