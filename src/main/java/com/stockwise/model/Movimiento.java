package com.stockwise.model;

public record Movimiento(long id, String fecha, long productoId, String productoCodigo, String productoNombre,
                         long sucursalId, String sucursalCodigo, TipoMovimiento tipo, int cantidad,
                         int stockResultante, String nota) {
}
