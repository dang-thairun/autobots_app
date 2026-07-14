# MVP keeps a bounded Write Queue for local JPEG drain

Kept Photos go through a bounded async Write Queue before Local Delivery. Required in MVP because Keep-All triples writes per Passage and Max-Sensor frames are large — unbounded in-memory buffering or blocking disk I/O on the camera path is an OOM/jank risk.
