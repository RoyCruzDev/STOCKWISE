package com.stockwise.model;

public enum Prioridad {
    CRITICA("Crítica", 4), ALTA("Alta", 3), MEDIA("Media", 2), BAJA("Baja", 1);

    private final String etiqueta;
    private final int peso;

    Prioridad(String etiqueta, int peso) {
        this.etiqueta = etiqueta;
        this.peso = peso;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public int peso() {
        return peso;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
