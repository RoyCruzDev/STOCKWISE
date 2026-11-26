package com.stockwise.model;

public record Categoria(long id, String nombre) {
    @Override
    public String toString() {
        return nombre;
    }
}
