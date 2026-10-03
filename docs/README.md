# KFE documentation

KFE owns financial execution and its maintenance boundary, not release authority
or deployment orchestration. Operational evidence is summarized in [STATUS](STATUS.md).

- [Financial API](operations/api/KFE.md)
- [Maintenance contract and persistence](operations/cell-maintenance.md)
- [HTTP, scheduled and callback inventory](operations/maintenance-entrypoints.md)
- [Payment-request maintenance](operations/payment-request-maintenance.md)
- [Wallet maintenance](operations/wallet-maintenance.md)
- [Channel producers and retention](operations/producer-maintenance.md)
- [Notification maintenance](operations/notification-maintenance.md)
- [Balance observation](operations/balance-observation-maintenance.md)
- [Balance ledger and derivation cursor participants](operations/balance-cursor-maintenance.md)
- [Fee settlement and balance movement participants](operations/fee-movement-maintenance.md)
- [Lightning liquidity and audit participants](operations/liquidity-audit-maintenance.md)
- [State, idempotency, outbox and internal request participants](operations/transaction-participant-maintenance.md)
- [Custodial observation and deposit continuations](operations/custodial-observation-maintenance.md)
- [Cold observation](operations/cold-observation-maintenance.md)
- [Network settlement and confirmations](operations/network-settlement-maintenance.md)
- [Bootstrap and tax classification](operations/bootstrap-tax-maintenance.md)
- [Dashboard, balance and transaction publications](operations/publisher-maintenance.md)
- [Address, key, statement, prepared and peer roots](operations/address-statement-execution-maintenance.md)
- [Execution batch and direct rail boundaries](operations/execution-outbox-maintenance.md)
- [Execution helper and after-completion children](operations/execution-helper-maintenance.md)
- [ZMQ callbacks and pending reactive refreshes](operations/reactive-refresh-maintenance.md)

Release deployment and recovery orchestration are owned by Deploy. A successful
KFE build or health response is not permission to update the complete Cell.
