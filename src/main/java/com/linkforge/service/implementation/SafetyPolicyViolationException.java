package com.linkforge.service.implementation;

/**
 * Exception thrown when a proposed file mutation violates path containment,
 * allowed file types, byte limits, or duplicate path policies.
 */
public class SafetyPolicyViolationException extends RuntimeException {
    public SafetyPolicyViolationException(String message) {
        super(message);
    }
}
