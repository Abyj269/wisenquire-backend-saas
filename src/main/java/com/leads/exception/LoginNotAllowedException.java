package com.leads.exception;

/** Thrown when a non-trusted user's credentials are correct but their
 * request IP doesn't match any trusted IP configured for their branches. */
public class LoginNotAllowedException extends RuntimeException {
    public LoginNotAllowedException(String message) {
        super(message);
    }
}
