package com.stockwise.model;

import java.time.LocalDate;

/**
 * Compra u orden de compra.
 *
 * @param fecha          fecha del pedido
 * @param estado         PENDIENTE (en tránsito), RECIBIDA o CANCELADA
 * @param fechaEsperada  fecha estimada de llegada (pedidos pendientes)
 * @param fechaRecepcion fecha en que ingresó la mercadería (nula si no se recibió)
 * @param origen         SISTEMA (registrada en STOCKWISE) o IMPORTACION (reporte ERP; no modificó el stock)
 */
public record Compra(long id, LocalDate fecha, long productoId, String productoCodigo, String productoNombre,
                     long sucursalId, String sucursalCodigo, Long proveedorId, int cantidad,
                     double costoUnitario, double total, String estado, LocalDate fechaEsperada,
                     LocalDate fechaRecepcion, String documento, String origen) {

    public static final String PENDIENTE = "PENDIENTE";
    public static final String RECIBIDA = "RECIBIDA";
    public static final String CANCELADA = "CANCELADA";
    public static final String ORIGEN_SISTEMA = "SISTEMA";
    public static final String ORIGEN_IMPORTACION = "IMPORTACION";

    public Compra(long id, LocalDate fecha, long productoId, String productoCodigo, String productoNombre,
                  long sucursalId, String sucursalCodigo, Long proveedorId, int cantidad, double costoUnitario,
                  double total) {
        this(id, fecha, productoId, productoCodigo, productoNombre, sucursalId, sucursalCodigo, proveedorId, cantidad,
                costoUnitario, total, RECIBIDA, null, fecha, null, ORIGEN_SISTEMA);
    }

    public boolean pendiente() {
        return PENDIENTE.equals(estado);
    }
}
