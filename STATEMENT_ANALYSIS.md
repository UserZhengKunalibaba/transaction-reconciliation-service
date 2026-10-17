# Synthetic Statement Format Notes

This repository contains synthetic fixtures for parser tests. No production statements or customer identifiers are included.

## Supported inputs

- Alipay CSV exports with transaction timestamps, merchant descriptions, amounts, payment method, and status.
- WeChat Pay XLSX exports with summary rows followed by transaction records.
- Maybank credit-card and savings-account PDF text extracted by Apache PDFBox.

## Normalization rules

- Dates are converted to a consistent transaction date/time representation.
- Debit and credit values are normalized into signed amounts.
- Descriptions retain enough source context for reconciliation while account identifiers are masked.
- Input records receive stable source references so duplicate imports can be detected.

## Security boundary

- Tests use generated account numbers, merchant names, phone numbers, and transaction IDs.
- Passwords are supplied at runtime and are never committed.
- Logs must not emit full account numbers, document contents, or credentials.
- Real statements should remain outside the repository and be covered by local ignore rules.

## Verification

Parser behavior is covered by unit and integration-style fixture tests under `transactionprocessor/src/test`. The private-document smoke test is disabled because it requires files that are intentionally not distributed with this portfolio repository.
