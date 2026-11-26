package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Prioridad;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.util.Formatters;

import java.util.ArrayList;
import java.util.List;

/**
 * Previene el sobreinventario y propone reducir compras:
 * <ul>
 *   <li>PREVENIR_SOBREINVENTARIO: stock por encima del máximo o de la cobertura umbral.</li>
 *   <li>REDUCIR_COMPRAS: demanda en descenso con cobertura superior al ciclo de compra.</li>
 * </ul>
 */
public class OverstockRule implements BusinessRule {

    @Override
    public String nombre() {
        return "Sobreinventario y reducción de compras";
    }

    @Override
    public List<Recomendacion> evaluar(AnalysisSnapshot s) {
        List<Recomendacion> out = new ArrayList<>();
        double umbral = s.settings().mesesSobreinventario();
        int horizonte = s.settings().horizonteMeses();
        for (ProductMetrics m : s.productos()) {
            double costo = m.producto().costo();
            if (m.estado() == EstadoInventario.SOBREINVENTARIO) {
                double nivelSano = Math.ceil(m.demandaMensual() * umbral);
                if (m.stockMaximo() > 0) {
                    nivelSano = Math.min(nivelSano, m.stockMaximo());
                }
                double exceso = Math.max(0, m.stock() - nivelSano);
                out.add(BusinessRule.crear(TipoRecomendacion.PREVENIR_SOBREINVENTARIO,
                        m.coberturaMeses() > umbral * 2 ? Prioridad.ALTA : Prioridad.MEDIA, m, null, null,
                        String.format("Stock de %d u equivale a %.1f meses de demanda (umbral %.1f meses)%s.",
                                m.stock(), m.coberturaMeses(), umbral,
                                m.stockMaximo() > 0 && m.stock() > m.stockMaximo()
                                        ? "; supera el stock máximo de " + m.stockMaximo() : ""),
                        "Suspender las compras de este producto hasta que el stock baje a " + (long) nivelSano
                                + " unidades.",
                        "Exceso estimado de " + (long) exceso + " u (" + Formatters.money(exceso * costo)
                                + " de capital inmovilizado).",
                        exceso * costo));
                continue;
            }
            ForecastResult fr = m.forecast();
            if (fr != null && fr.tendencia() == ForecastResult.Tendencia.DECRECIENTE && m.demandaMensual() > 0
                    && m.estado() == EstadoInventario.NORMAL) {
                double mediaHist = fr.historia().length == 0 ? 0
                        : java.util.Arrays.stream(fr.historia()).average().orElse(0);
                double caida = mediaHist > 0 ? Math.min(0.9, -fr.pendiente() * horizonte / mediaHist) : 0;
                if (caida < 0.05) {
                    continue;
                }
                double unidadesMenos = Math.abs(fr.pendiente()) * horizonte;
                out.add(BusinessRule.crear(TipoRecomendacion.REDUCIR_COMPRAS, Prioridad.MEDIA, m, null, null,
                        String.format("La demanda cae %.1f u/mes según la tendencia histórica.", Math.abs(fr.pendiente())),
                        String.format("Reducir las cantidades de compra en torno a %.0f%% en el próximo ciclo.",
                                caida * 100),
                        "Tendencia decreciente estadísticamente significativa en " + fr.historia().length
                                + " meses de historial.",
                        unidadesMenos * costo));
            }
        }
        return out;
    }
}
