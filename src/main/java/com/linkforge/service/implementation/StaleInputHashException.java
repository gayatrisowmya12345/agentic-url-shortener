package com.linkforge.service.implementation;

/**
 * Exception thrown when optimistic concurrency hash check fails on a target file.
 */
public class StaleInputHashException extends RuntimeException {
    public StaleInputHashException(String message) {
        super(message);
    }
}
