package com.stockwise.recommendation;

import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.SupplyInfo;
import com.stockwise.model.Prioridad;
import com.stockwise.prediction.DemandPattern;

/**
 * Decisión estratégica de un producto con los niveles de inventario que la sustentan.
 *
 * @param stockSeguridad    stock de seguridad (unidades)
 * @param puntoReorden      ROP sobre la posición de inventario (stock + tránsito)
 * @param nivelObjetivo     nivel objetivo de reposición
 * @param cantidadSugerida  unidades a comprar (solo RECOMPRAR / NEGOCIAR con necesidad de compra)
 * @param impactoEstimado   efecto económico estimado con los datos (nulo si no puede estimarse)
 * @param disponibilidad    proporción de días con stock en los 90 días previos al corte (nula si no hay datos)
 * @param roiEsperado       ROI esperado de la compra sugerida (nulo si no aplica)
 */
public record ProductDecision(ProductMetrics metrics, SupplyInfo suministro, DecisionEstrategica decision,
                              Prioridad prioridad, DemandPattern patron, String modelo, double stockSeguridad,
                              double puntoReorden, double nivelObjetivo, int cantidadSugerida, Double impactoEstimado,
                              String motivo, String accion, Double disponibilidad, Double roiEsperado) {

    public long productoId() {
        return metrics.productoId();
    }

    public int posicionInventario() {
        return metrics.stock() + suministro.enTransito();
    }
}
