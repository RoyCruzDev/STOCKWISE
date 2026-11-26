package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Prioridad;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;
import com.stockwise.util.Formatters;

import java.util.ArrayList;
import java.util.List;

/** Previene desabastecimientos: productos agotados o cuya cobertura es menor al tiempo de reposición. */
public class StockoutPreventionRule implements BusinessRule {

    @Override
    public String nombre() {
        return "Prevención de desabastecimiento";
    }

    @Override
    public List<Recomendacion> evaluar(AnalysisSnapshot s) {
        List<Recomendacion> out = new ArrayList<>();
        int horizonte = s.settings().horizonteMeses();
        for (ProductMetrics m : s.productos()) {
            if (m.estado() != EstadoInventario.AGOTADO && m.estado() != EstadoInventario.RIESGO_AGOTAMIENTO) {
                continue;
            }
            if (m.demandaMensual() <= 0 && m.stock() > 0) {
                continue;
            }
            double ltMeses = m.leadTimeDias() / ProductMetrics.DIAS_POR_MES;
            double demandaLt = m.demandaMensual() * ltMeses;
            double faltante = Math.max(0, demandaLt - m.stock());
            int sugerido = (int) Math.ceil(Math.max(m.demandaMensual() * (ltMeses + horizonte), m.stockMinimo())
                    - m.stock());
            double margenPerdido = faltante * Math.max(0, m.producto().margenUnitario());
            boolean agotado = m.estado() == EstadoInventario.AGOTADO;
            String problema = agotado
                    ? "Producto agotado con demanda de " + Formatters.number(m.demandaMensual()) + " u/mes."
                    : String.format("El stock (%d u) cubre %.0f días y la reposición tarda %d días.", m.stock(),
                    m.coberturaDias(), m.leadTimeDias());
            out.add(BusinessRule.crear(TipoRecomendacion.PREVENIR_DESABASTECIMIENTO,
                    agotado ? Prioridad.CRITICA : Prioridad.ALTA, m, null, null, problema,
                    "Emitir orden de compra por aproximadamente " + Math.max(sugerido, 1) + " unidades"
                            + " (validar en el módulo de Compras con el presupuesto disponible).",
                    String.format("Demanda estimada durante la reposición: %.0f u; stock mínimo %d u.%s", demandaLt,
                            m.stockMinimo(), m.demandaConfiable() ? "" : " Historial corto: estimación referencial."),
                    margenPerdido > 0 ? margenPerdido : null));
        }
        return out;
    }
}
