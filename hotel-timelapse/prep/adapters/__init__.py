"""Adapters turn one source system's export into BUILD_SPEC §3 records.

Adding a property on a different PMS/POS/CRS means adding a module here, not
touching the app (§0). Property-specific mappings live in each adapter's
config dicts, never in the app.
"""
