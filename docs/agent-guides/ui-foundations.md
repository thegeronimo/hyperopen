# Hyperopen UI Foundations for Agents

## Purpose and Scope
- This guide applies only when tasks touch UI-facing code.
- Use this guide with `/hyperopen/docs/FRONTEND.md`.
- Out of scope for this guide: AR/VR, voice UI, adaptive AI interface behavior, and platform-specific iOS/Android conventions.

## Ethical Design and User Agency (MUST)
- MUST avoid dark patterns and preserve explicit user intent.
- MUST keep entry and exit paths equally easy (no roach motel).
- MUST ensure controls perform their labeled action (no bait and switch).
- MUST keep opt-out language neutral (no confirmshaming).
- MUST avoid visual misdirection that hides safer or cancel actions.

## Usability and Interaction Baseline (MUST)
- MUST provide timely status feedback for user actions (pending, success, failure).
- MUST use user-facing language and avoid internal jargon in UI copy.
- MUST provide clear cancel or undo paths for recoverable/destructive actions where feasible.
- MUST keep component behavior and terminology consistent across views.
- MUST prevent errors before submit with constraints, sensible defaults, and inline validation.
- MUST prefer recognition over recall by keeping required context visible or easily retrievable.
- MUST NOT hide critical information or critical actions behind hover-only interactions.

## Cognitive Load Rules (MUST)
- MUST chunk complex workflows into smaller sections.
- MUST use progressive disclosure for advanced options and secondary detail.
- MUST minimize competing choices in high-stakes workflows.
- MUST establish clear visual hierarchy so primary actions are obvious.

## Accessibility Baseline (MUST)
- MUST meet WCAG 2.2 AA for UI changes.
- MUST support keyboard-only operation for all interactive controls.
- MUST provide visible focus indicators and logical focus order.
- MUST ensure interactive target size is at least 24x24 CSS pixels.
- MUST include semantic labels for inputs and `alt` text for meaningful images.
- MUST meet contrast requirements and avoid color-only meaning.
- MUST preserve compatibility with browser autofill and previously entered values where practical.
- MUST ensure focus is not obscured by sticky headers, sticky footers, or overlays.

## UI Validation Checklist
- [ ] Yes/No: No dark pattern behavior exists in the interaction.
- [ ] Yes/No: Primary and secondary actions are clear and truthful.
- [ ] Yes/No: End-to-end keyboard operation works with visible focus states.
- [ ] Yes/No: Cognitive load is reduced via chunking, defaults, and progressive disclosure.
- [ ] Yes/No: Contrast, labels, and non-color cues satisfy accessibility expectations.
