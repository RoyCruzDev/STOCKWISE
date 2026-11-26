package com.stockwise.service;

/** Error de validación de reglas de negocio; su mensaje se muestra directamente al usuario. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
