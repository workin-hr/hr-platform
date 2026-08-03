# Quality Gate Cadence

## Goal

Define which validation layers are cheap enough for continuous use and which should be scheduled or release-gated.

## Rules

- expensive load, stress, and soak tests do not run on every commit
- migration and compatibility tests expand as implementation appears
- human approval remains mandatory even when automated checks pass
