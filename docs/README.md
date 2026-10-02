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

Release deployment and recovery orchestration are owned by Deploy. A successful
KFE build or health response is not permission to update the complete Cell.
