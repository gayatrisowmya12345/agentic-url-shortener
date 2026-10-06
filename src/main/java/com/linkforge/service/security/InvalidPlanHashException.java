package com.linkforge.service.security;

public class InvalidPlanHashException extends RuntimeException {
    public InvalidPlanHashException(String message) {
        super(message);
    }
}
