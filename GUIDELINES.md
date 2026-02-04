# Hyperopen Guidelines (Addendum)

This document supplements `.cursorrules` and should be read alongside it.

## Core Goals
- Aim for feature parity with `app.hyperliquid.xyz`.

## Source Of Truth
- The official Hyperliquid documentation is the first source of truth for how to interact with the protocol: [https://hyperliquid.gitbook.io/hyperliquid-docs](https://hyperliquid.gitbook.io/hyperliquid-docs).

## Behavioral Guidance
- When implementing or updating features, map requirements to documented protocol behavior first, then align UI/UX with `app.hyperliquid.xyz`.
- If documentation and observed behavior appear to differ, document the discrepancy and prioritize the official docs unless a clear product decision says otherwise.
- Keep changes minimal and consistent with existing patterns; prefer extending current code over introducing new abstractions.
- Avoid speculative behavior changes; validate against docs and parity goals before altering flows.
