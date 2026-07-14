# MVP delivery is local disk only; cloud upload is a later phase

Passage success in MVP is writing Kept Photos to on-device storage. Remote/cloud upload is intentionally deferred so the capture pipeline stays offline-capable and unblocked by field network, auth, and sync concerns. Design local writes as the stable handoff point a future uploader can consume.
