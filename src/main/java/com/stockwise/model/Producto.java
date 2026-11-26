package com.stockwise.model;

/**
 * Producto del catálogo. {@code leadTimeDias} nulo significa "usar el tiempo de reposición por defecto".
 */
public record Producto(long id, String codigo, String nombre, Long categoriaId, String categoria,
                       Long proveedorId, String proveedor, double precioVenta, double costo,
                       int stockMinimo, int stockMaximo, Integer leadTimeDias, boolean activo) {

    public double margenUnitario() {
        return precioVenta - costo;
    }

    /** Margen sobre el precio de venta (0..1). */
    public double margenPorcentual() {
        return precioVenta <= 0 ? 0 : (precioVenta - costo) / precioVenta;
    }

    @Override
    public String toString() {
        return codigo + " - " + nombre;
    }
}
