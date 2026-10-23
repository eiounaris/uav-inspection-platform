package com.uav.inspection.messaging;

/** A valid JSON envelope that does not correspond to a durable business event. */
public class InvalidMessageException extends RuntimeException {
    public InvalidMessageException() { super("Invalid durable message identity"); }
}
