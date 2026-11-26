package com.stockwise.database;

/** Excepción no comprobada que envuelve errores de acceso a datos. */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataAccessException(String message) {
        super(message);
    }
}
