package com.uav.inspection.common.lease;

/** A worker without a verified processing lease must leave recovery to a new owner. */
public final class ProcessingLeaseLostException extends RuntimeException {
    public ProcessingLeaseLostException() { super("Processing lease is no longer verified"); }
}
