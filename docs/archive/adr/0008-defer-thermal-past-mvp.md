# Defer thermal throttling past MVP

MVP prioritizes a working Passage capture path (detect → arm → fire → local Kept Photos) without ThermalGuard / adaptive backoff. Docs that put thermal in the critical path are deferred — accept higher risk of heat on long deployments until a post-MVP pass adds throttling. Max-Sensor mode remains operator-opt-in partly for this reason.
