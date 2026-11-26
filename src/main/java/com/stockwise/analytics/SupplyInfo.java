package com.stockwise.analytics;

import java.time.LocalDate;

/**
 * Datos de abastecimiento de un producto.
 *
 * @param enTransito          unidades en órdenes de compra pendientes de recibir
 * @param cicloCompraDias     periodo de revisión del proveedor (nulo = usar el horizonte de cobertura configurado)
 * @param desviacionLeadTime  desviación estándar observada del tiempo de entrega en días (0 si no hay datos)
 * @param origenLeadTime      de dónde proviene el tiempo de reposición usado (producto, proveedor, observado, defecto)
 * @param proximoPedido       fecha sugerida del próximo pedido según el ciclo (nula si no se puede calcular)
 */
public record SupplyInfo(long productoId, Long proveedorId, String proveedor, int enTransito, Integer cicloCompraDias,
                         double desviacionLeadTime, String origenLeadTime, LocalDate proximoPedido) {

    public static SupplyInfo vacio(long productoId) {
        return new SupplyInfo(productoId, null, null, 0, null, 0, "Producto o valor por defecto", null);
    }

    /** Periodo de revisión en meses: ciclo del proveedor o, si no existe, el horizonte configurado. */
    public double cicloMeses(int horizonteMesesDefecto) {
        return cicloCompraDias != null && cicloCompraDias > 0 ? cicloCompraDias / ProductMetrics.DIAS_POR_MES
                : horizonteMesesDefecto;
    }
}
