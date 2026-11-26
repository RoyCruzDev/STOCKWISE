package com.stockwise.recommendation;

import java.util.List;

/** Plan de compras completo con su consumo de presupuesto. */
public record PurchasePlan(List<PurchaseRecommendation> lineas, double presupuestoDisponible,
                           double totalAprobado, double totalSugerido, List<String> notas) {

    public double saldo() {
        return presupuestoDisponible - totalAprobado;
    }

    public List<PurchaseRecommendation> aComprar() {
        return lineas.stream().filter(l -> l.cantidadAprobada() > 0).toList();
    }

    public List<PurchaseRecommendation> conRiesgo() {
        return lineas.stream().filter(PurchaseRecommendation::riesgoAgotamiento).toList();
    }

    public List<PurchaseRecommendation> noRequieren() {
        return lineas.stream()
                .filter(l -> l.decision() == PurchaseRecommendation.Decision.NO_REQUIERE).toList();
    }
}
