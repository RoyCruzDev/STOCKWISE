package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Prioridad;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.util.Formatters;

import java.util.ArrayList;
import java.util.List;

/**
 * Ajuste de precios:
 * <ul>
 *   <li>Margen por debajo del mínimo configurado → subir el precio hasta alcanzarlo.</li>
 *   <li>Demanda creciente con stock en riesgo y margen sano → subida moderada (3%).</li>
 * </ul>
 */
public class PricingRule implements BusinessRule {

    @Override
    public String nombre() {
        return "Ajuste de precios";
    }

    @Override
    public List<Recomendacion> evaluar(AnalysisSnapshot s) {
        List<Recomendacion> out = new ArrayList<>();
        double margenMin = s.settings().margenMinimo();
        for (ProductMetrics m : s.productos()) {
            double precio = m.producto().precioVenta();
            double costo = m.producto().costo();
            if (costo <= 0 || precio <= 0) {
                continue;
            }
            double unidadesAnuales = m.unidades365() > 0 ? m.unidades365() : m.demandaMensual() * 12;
            if (m.margen() < margenMin) {
                double nuevoPrecio = costo / (1 - margenMin);
                boolean perdida = precio < costo;
                out.add(BusinessRule.crear(TipoRecomendacion.AJUSTAR_PRECIO,
                        perdida ? Prioridad.CRITICA : Prioridad.ALTA, m, null, null,
                        perdida ? String.format("Se vende bajo el costo: precio %s, costo %s.",
                                Formatters.money(precio), Formatters.money(costo))
                                : String.format("Margen de %.1f%%, inferior al mínimo de %.1f%%.", m.margen() * 100,
                                margenMin * 100),
                        "Ajustar el precio de venta de " + Formatters.money(precio) + " a "
                                + Formatters.money(nuevoPrecio) + ".",
                        String.format("Con el nuevo precio el margen alcanza %.1f%%. Beneficio calculado sobre "
                                + "%.0f u/año sin considerar la elasticidad de la demanda.", margenMin * 100,
                                unidadesAnuales),
                        (nuevoPrecio - precio) * unidadesAnuales));
                continue;
            }
            ForecastResult fr = m.forecast();
            if (fr != null && fr.tendencia() == ForecastResult.Tendencia.CRECIENTE
                    && m.coberturaDias() < m.leadTimeDias() * 1.5 && m.demandaMensual() > 0) {
                double nuevoPrecio = precio * 1.03;
                out.add(BusinessRule.crear(TipoRecomendacion.AJUSTAR_PRECIO, Prioridad.BAJA, m, null, null,
                        String.format("Demanda creciente (+%.1f u/mes) con cobertura de solo %.0f días.",
                                fr.pendiente(), m.coberturaDias()),
                        "Evaluar una subida moderada del 3% (a " + Formatters.money(nuevoPrecio) + ").",
                        "La demanda supera la capacidad de reposición: un ajuste leve mejora el margen "
                                + "sin afectar significativamente el volumen.",
                        (nuevoPrecio - precio) * m.demandaMensual() * 12));
            }
        }
        return out;
    }
}
