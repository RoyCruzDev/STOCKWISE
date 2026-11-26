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
 * Análisis de rentabilidad (Pareto sobre la utilidad bruta de los últimos 12 meses):
 * <ul>
 *   <li>Rentables: productos que acumulan el 80% de la utilidad (máximo 10).</li>
 *   <li>Poco rentables: utilidad nula/negativa o inventario inmovilizado mayor que la utilidad anual.</li>
 * </ul>
 */
public class ProfitabilityRule implements BusinessRule {

    @Override
    public String nombre() {
        return "Rentabilidad de productos";
    }

    @Override
    public List<Recomendacion> evaluar(AnalysisSnapshot s) {
        List<Recomendacion> out = new ArrayList<>();
        List<ProductMetrics> conVentas = s.productos().stream().filter(m -> m.importe365() > 0)
                .sorted(Comparator.comparingDouble(ProductMetrics::utilidad365).reversed()).toList();
        double utilidadTotal = conVentas.stream().mapToDouble(m -> Math.max(0, m.utilidad365())).sum();
        if (utilidadTotal > 0) {
            double acumulada = 0;
            int contador = 0;
            for (ProductMetrics m : conVentas) {
                if (m.utilidad365() <= 0 || acumulada >= utilidadTotal * 0.8 || contador >= 10) {
                    break;
                }
                acumulada += m.utilidad365();
                contador++;
                out.add(BusinessRule.crear(TipoRecomendacion.PRODUCTO_RENTABLE, Prioridad.BAJA, m, null, null,
                        String.format("Aporta %s de utilidad anual (%.1f%% del total).",
                                Formatters.money(m.utilidad365()), m.utilidad365() / utilidadTotal * 100),
                        "Priorizar su disponibilidad: no permitir quiebres de stock y mantenerlo visible.",
                        String.format("Forma parte del grupo de productos que genera el 80%% de la utilidad. "
                                + "Margen %.1f%%.", m.margen() * 100),
                        null));
            }
        }
        for (ProductMetrics m : s.productos()) {
            if (m.importe365() > 0 && m.utilidad365() <= 0) {
                out.add(BusinessRule.crear(TipoRecomendacion.PRODUCTO_POCO_RENTABLE, Prioridad.ALTA, m, null, null,
                        "Utilidad nula o negativa en los últimos 12 meses (" + Formatters.money(m.utilidad365()) + ").",
                        "Revisar costo y precio; si no es estratégico, evaluar su descontinuación.",
                        "Las ventas no cubren el costo de adquisición.",
                        -m.utilidad365()));
            } else if (m.utilidad365() > 0 && m.valorInventario() > m.utilidad365() * 2) {
                out.add(BusinessRule.crear(TipoRecomendacion.PRODUCTO_POCO_RENTABLE, Prioridad.MEDIA, m, null, null,
                        String.format("El inventario inmovilizado (%s) es %.1f veces su utilidad anual (%s).",
                                Formatters.money(m.valorInventario()), m.valorInventario() / m.utilidad365(),
                                Formatters.money(m.utilidad365())),
                        "Reducir el stock objetivo y comprar en lotes más pequeños.",
                        "El retorno sobre el capital invertido en este producto es bajo.",
                        null));
            }
        }
        return out;
    }
}
