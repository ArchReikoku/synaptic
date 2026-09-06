# Changelog (since 2.0.0)

## New
- Added full run system improvements: per-run dimensions, fresh-seed world resets, and better lobby/run flow.
- Added persistence across restarts for run and lobby state.
- Expanded session/ranked stats tracking and syncing.
- Upgraded the death report experience and ownership flow.
- Added and expanded client UI around runs, lobby flow, and session reporting.

## Changes
- Reworked world/reset lifecycle so the "Next Run" flow is more reliable.
- Improved networking and sync around stats and reporting.
- `Mob Anger` default is now off.
- Settings screen text and layout were cleaned up for readability.

## Fixes
- Fixed settings footer layout.
- Shortened and cleaned feature descriptions.
- Stopped forcibly enabling `keepInventory` in settings flow.

## Internal
- Large refactor across mixins, world management, stats, and networking to support the above features consistently.
