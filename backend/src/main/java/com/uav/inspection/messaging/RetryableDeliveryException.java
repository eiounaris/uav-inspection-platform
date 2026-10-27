package com.uav.inspection.messaging;

/** Keep the broker delivery until a final result or an independent recovery plan has committed. */
public final class RetryableDeliveryException extends RuntimeException {
    public RetryableDeliveryException() { super("Durable processing handoff is not committed"); }
}
