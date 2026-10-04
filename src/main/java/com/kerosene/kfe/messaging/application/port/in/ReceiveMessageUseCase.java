package com.kerosene.kfe.messaging.application.port.in;

import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;

/** Inbound application boundary for an authenticated, durable message submission. */
public interface ReceiveMessageUseCase {

    void receive(MessageEnvelope message, WorkloadIdentity workload);
}
