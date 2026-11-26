package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Prioridad;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;
import com.stockwise.util.Formatters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Negociación con proveedores: productos del 20% superior en volumen de compra anual (unidades × costo)
 * con margen ajustado (menor que el mínimo + 10 puntos). Se estima un 5% de ahorro en el costo.
 */
public class SupplierNegotiationRule implements BusinessRule {

    static final double AHORRO_OBJETIVO = 0.05;

    @Override
    public String nombre() {
        return "Negociación con proveedores";
    }

    @Override
    public List<Recomendacion> evaluar(AnalysisSnapshot s) {
        List<ProductMetrics> conVolumen = s.productos().stream()
                .filter(m -> m.costoVendido365() > 0)
                .sorted(Comparator.comparingDouble(ProductMetrics::costoVendido365).reversed())
                .toList();
        List<Recomendacion> out = new ArrayList<>();
        if (conVolumen.isEmpty()) {
            return out;
        }
        int top = Math.max(1, (int) Math.ceil(conVolumen.size() * 0.2));
        double total = conVolumen.stream().mapToDouble(ProductMetrics::costoVendido365).sum();
        double margenObjetivo = s.settings().margenMinimo() + 0.10;
        for (int i = 0; i < top; i++) {
            ProductMetrics m = conVolumen.get(i);
            if (m.margen() >= margenObjetivo) {
                continue;
            }
            double ahorro = m.costoVendido365() * AHORRO_OBJETIVO;
            String proveedor = m.producto().proveedor() == null ? "el proveedor" : m.producto().proveedor();
            out.add(BusinessRule.crear(TipoRecomendacion.NEGOCIAR_PROVEEDOR, Prioridad.MEDIA, m, null, null,
                    String.format("Alto volumen de compra (%s/año, %.1f%% del total) con margen de %.1f%%.",
                            Formatters.money(m.costoVendido365()), m.costoVendido365() / total * 100,
                            m.margen() * 100),
                    "Negociar con " + proveedor + " un descuento por volumen de al menos "
                            + (int) (AHORRO_OBJETIVO * 100) + "% o mejores condiciones de pago.",
                    "Está entre el 20% de productos con mayor volumen de compra y su margen es inferior a "
                            + String.format("%.0f%%", margenObjetivo * 100) + ".",
                    ahorro));
        }
        return out;
    }
}
