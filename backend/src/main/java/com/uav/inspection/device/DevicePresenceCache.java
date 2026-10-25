package com.uav.inspection.device;

import java.time.Instant;

/** A disposable acceleration layer. It is never an authentication or event-deduplication store. */
public interface DevicePresenceCache {
    Instant find(String droneId);
    void remember(String droneId,Instant receivedAt);
}
