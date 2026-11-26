package com.stockwise.model;

public enum TipoRecomendacion {
    TRASLADO("Traslado entre sucursales"),
    PROMOCION("Aplicar promoción"),
    NEGOCIAR_PROVEEDOR("Negociar con proveedor"),
    LIQUIDAR("Liquidar producto"),
    AJUSTAR_PRECIO("Ajustar precio de venta"),
    REDUCIR_COMPRAS("Reducir compras"),
    PREVENIR_SOBREINVENTARIO("Prevenir sobreinventario"),
    PREVENIR_DESABASTECIMIENTO("Prevenir desabastecimiento"),
    PRODUCTO_RENTABLE("Producto rentable"),
    PRODUCTO_POCO_RENTABLE("Producto poco rentable");

    private final String etiqueta;

    TipoRecomendacion(String etiqueta) {
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
