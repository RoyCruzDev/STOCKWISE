package com.stockwise.model;

import java.time.LocalDate;

public record Venta(long id, LocalDate fecha, long productoId, String productoCodigo, String productoNombre,
                    long sucursalId, String sucursalCodigo, double cantidad, double precioUnitario, double total,
                    String referencia) {
}
