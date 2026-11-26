package com.stockwise.analytics;

public enum EstadoInventario {
    AGOTADO("Agotado"),
    RIESGO_AGOTAMIENTO("Riesgo de agotarse"),
    NORMAL("Normal"),
    SOBREINVENTARIO("Exceso de stock"),
    SIN_MOVIMIENTO("Sin movimiento");

    private final String etiqueta;

    EstadoInventario(String etiqueta) {
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
