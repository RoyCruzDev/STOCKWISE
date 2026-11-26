package com.stockwise.model;

public enum TipoMovimiento {
    ENTRADA("Entrada"),
    SALIDA("Salida"),
    VENTA("Venta"),
    COMPRA("Compra"),
    AJUSTE("Ajuste"),
    TRASLADO_SALIDA("Traslado (salida)"),
    TRASLADO_ENTRADA("Traslado (entrada)"),
    IMPORTACION("Importación");

    private final String etiqueta;

    TipoMovimiento(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
