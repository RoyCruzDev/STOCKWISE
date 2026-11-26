package com.stockwise.model;

/**
 * Proveedor. {@code leadTimeDias} y {@code cicloCompraDias} nulos significan "usar los valores por defecto".
 *
 * @param cicloCompraDias cada cuántos días se emite un pedido a este proveedor (periodo de revisión)
 */
public record Proveedor(long id, String nombre, String contacto, String telefono, String email, Integer leadTimeDias,
                        Integer cicloCompraDias) {

    public Proveedor(long id, String nombre, String contacto, String telefono, String email, Integer leadTimeDias) {
        this(id, nombre, contacto, telefono, email, leadTimeDias, null);
    }

    @Override
    public String toString() {
        return nombre;
    }
}
