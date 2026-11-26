package com.stockwise.model;

/** Presupuesto de compras de un periodo (YYYY-MM). sucursalId nulo = presupuesto general. */
public record Presupuesto(long id, String periodo, Long sucursalId, String sucursalCodigo, double monto,
                          String descripcion) {
}
