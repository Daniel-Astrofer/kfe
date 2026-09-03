# Agent guide — Kerosene KFE

## Scope

KFE owns financial execution, wallets, reconciliation and ledger-facing
behavior. It consumes Contracts, Shared, Vault, Node and Rails interfaces.

## Documentation

- Start at `docs/README.md`.
- Put financial boundaries and integrations in `architecture/`, API facts in
  `reference/`, and runbooks in `operations/`.
- Keep historical migration material in `history/`; do not represent it as an
  active operational procedure.

## Safety and integration

- Do not include other repositories' implementations, deployment manifests or
  secrets.
- Use versioned contracts and document consumer-impacting API changes.

## Verification

Run the relevant Gradle checks and update STATUS, API catalog and runbook
evidence when financial behavior changes.
