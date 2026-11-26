package com.stockwise.model;

/** Existencias de un producto en una sucursal. */
public record InventarioItem(long id, long productoId, String productoCodigo, String productoNombre,
                             long sucursalId, String sucursalCodigo, String sucursalNombre,
                             int stock, double costo, double precioVenta, int stockMinimo, int stockMaximo,
                             String fechaActualizacion) {

    public double valorCosto() {
        return stock * costo;
    }

    public double valorVenta() {
        return stock * precioVenta;
    }
}
