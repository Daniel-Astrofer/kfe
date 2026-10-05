package com.kerosene.kfe.messaging.application.port.out;

import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;

public interface AuthenticatedMessageTransport {
    void publish(MessageEnvelope message, WorkloadIdentity workload);
}
