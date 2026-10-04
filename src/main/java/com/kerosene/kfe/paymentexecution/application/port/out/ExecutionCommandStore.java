package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.ScheduleExternalExecutionCommand;

import java.util.UUID;

/** Durable boundary used to schedule at-least-once external execution. */
public interface ExecutionCommandStore {
    UUID enqueue(ScheduleExternalExecutionCommand command);
}
