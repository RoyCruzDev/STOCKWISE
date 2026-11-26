package com.stockwise.model;

public record Sucursal(long id, String codigo, String nombre, String ciudad, String direccion, boolean activa) {
    @Override
    public String toString() {
        return codigo + " - " + nombre;
    }
}
