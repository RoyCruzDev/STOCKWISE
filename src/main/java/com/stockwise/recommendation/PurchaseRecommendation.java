package com.stockwise.recommendation;

import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Prioridad;

/**
 * Recomendación de compra de un producto.
 *
 * @param demandaPeriodo     demanda estimada durante el tiempo de reposición + ciclo de compra
 * @param stockSeguridad     stock de seguridad calculado
 * @param puntoReorden       nivel de posición de inventario que dispara la compra (ROP)
 * @param cantidadSugerida   unidades necesarias según la demanda
 * @param cantidadAprobada   unidades que caben en el presupuesto
 * @param costoTotal         costo de la cantidad aprobada
 * @param enTransito         unidades en órdenes pendientes ya descontadas de la necesidad
 * @param nivelObjetivo      nivel objetivo (order-up-to) al que se repone la posición de inventario
 * @param roiEsperado        margen bruto esperado de lo aprobado / inversión (nulo si no hay inversión o margen)
 */
public record PurchaseRecommendation(ProductMetrics metrics, double demandaPeriodo, double stockSeguridad,
                                     double puntoReorden, int cantidadSugerida, int cantidadAprobada,
                                     double costoUnitario, double costoTotal, Prioridad prioridad, Decision decision,
                                     boolean riesgoAgotamiento, String motivo, int enTransito, double nivelObjetivo,
                                     Double roiEsperado) {

    public PurchaseRecommendation(ProductMetrics metrics, double demandaPeriodo, double stockSeguridad,
                                  double puntoReorden, int cantidadSugerida, int cantidadAprobada,
                                  double costoUnitario, double costoTotal, Prioridad prioridad, Decision decision,
                                  boolean riesgoAgotamiento, String motivo) {
        this(metrics, demandaPeriodo, stockSeguridad, puntoReorden, cantidadSugerida, cantidadAprobada, costoUnitario,
                costoTotal, prioridad, decision, riesgoAgotamiento, motivo, 0, 0, null);
    }

    public enum Decision {
        COMPRAR("Comprar"),
        COMPRA_PARCIAL("Compra parcial"),
        SIN_PRESUPUESTO("Sin presupuesto"),
        NO_REQUIERE("No requiere compra");

        private final String etiqueta;

        Decision(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    public double costoSugerido() {
        return cantidadSugerida * costoUnitario;
    }

    /** Posición de inventario: stock físico + en tránsito. */
    public int posicionInventario() {
        return metrics.stock() + enTransito;
    }
}
