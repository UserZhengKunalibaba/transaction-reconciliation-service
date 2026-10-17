# Transaction Reconciliation Service

A Spring Boot and React application for importing financial statements, normalizing transactions, detecting duplicates, applying reconciliation rules, and exporting a clean ledger.

This repository is maintained as a portfolio adaptation. It demonstrates how I audit a financial-data codebase, remove personal-looking fixtures and embedded credentials, preserve the reconciliation design, and make verification repeatable. See [UPSTREAM.md](UPSTREAM.md) for the original source and exact adaptation record.

## What the project demonstrates

- Java 17-compatible code running on Java 21
- Spring Boot, Spring Data JPA, Flyway, and PostgreSQL
- PDF, CSV, XLSX, and ZIP statement ingestion
- source-specific parsers and a canonical transaction model
- deterministic duplicate detection and idempotent imports
- rule-based reconciliation for transfers, card payments, and wallet top-ups
- asynchronous statement processing
- React, TypeScript, and Vite user interface
- Docker Compose deployment
- unit and H2-backed integration tests

## Processing pipeline

```text
Statement upload
  -> source-specific parser
  -> normalized transaction
  -> deterministic deduplication
  -> batch persistence
  -> reconciliation rules
  -> reconciled ledger
  -> preview and CSV export
```

The upload bucket declares the statement source. Parsers do not guess the source from transaction content. This keeps normalization behavior deterministic and makes failures easier to diagnose.

## Repository layout

```text
transactionprocessor/   Spring Boot backend
frontend/               React and TypeScript UI
docker-compose.yml      PostgreSQL, backend, and frontend stack
```

Backend packages include parsers, normalization, upload orchestration, reconciliation rules, wallet export, persistence, and REST controllers.

## Build and test the backend

Requirements:

- Java 17 or newer

```bash
cd transactionprocessor
./mvnw --batch-mode --no-transfer-progress test
```

The automated tests use H2 where database behavior is required. No personal bank statement is needed; repository fixtures contain synthetic records only.

## Run the complete stack

Requirements:

- Docker with Docker Compose

```bash
cp .env.example .env  # if an example file is present in your environment
docker compose up -d --build
```

Default endpoints:

- Frontend: `http://127.0.0.1:3001`
- Backend: `http://127.0.0.1:8080`
- PostgreSQL: `127.0.0.1:5433`

Set `DB_PASSWORD` explicitly for any shared environment. `TNG_PDF_PASSWORD` is optional and must be supplied externally when an encrypted PDF requires it.

## Data-safety notes

- Test CSV and XLSX fixtures are synthetic.
- Payment identifiers and card suffixes are fictional.
- No statement password is committed.
- Uploaded statements and runtime data directories are ignored by Git.
- Production credentials are not included.

## Review focus

- `transactionprocessor/.../parser` for file parsing
- `transactionprocessor/.../normalizer` for canonical mapping
- `transactionprocessor/.../recon` for matching rules
- `transactionprocessor/.../service` for processing orchestration
- `transactionprocessor/.../wallet` for CSV export
- `transactionprocessor/src/test` for verification coverage

## Verification boundary

The backend test suite can run without Docker. Docker Compose, PostgreSQL integration, OCR behavior, and the rendered frontend require separate environment testing and are not implied by a successful unit-test run.

## License and attribution

MIT licensed. The original copyright notice is retained in [LICENSE](LICENSE). See [UPSTREAM.md](UPSTREAM.md) for upstream authorship and the adaptation record.
