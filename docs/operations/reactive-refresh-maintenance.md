# Reactive refresh admission

The debounced worker admits before clearing pending wallet/all-wallet targets,
index rebuild or calling observation/balance services. Missing injection, drain
or admission storage outage pauses the tick and preserves its in-memory targets
for a later independently admitted tick. It does not automatically resume the
maintenance mode. The owned scheduler shuts down with the bean.

Signal collection is an in-memory observational hint, not a financial admission
or a persisted continuation. The eventual worker must admit anew. Already
admitted synchronous nested observations can finish during drain. Coalescing,
provider selection and financial algorithms are unchanged; caught errors and
positive returns retain UNCERTAIN. Provider failure is not a proven retry or
recovery result.

The real ZMQ workers now enter `bitcoin-zmq.message` before gap-triggered refresh,
matching/ingest or pending-target enqueue. Sequence telemetry advances only after
the admitted handler returns; rejection/propagated errors preserve it. A rejected
message does not inherit an outbox/transaction ID as admission. Socket startup is
observational and does not admit an indefinite daemon as completed work.

This closes loss of pending targets on admission rejection, not restart replay:
target hints and sequence telemetry are not stored durably. Existing per-observer
caught failures remain uncertainty, not durable acknowledgement. Tests exercise
the actual worker callback boundary without sockets; real ZMQ delivery/reconnect,
unsigned sequence wrapping, restart/source reconciliation, post-effect retry and
full-Cell drain certification remain unqualified. A successful unit test does not
remove any unknown coverage blocker.
