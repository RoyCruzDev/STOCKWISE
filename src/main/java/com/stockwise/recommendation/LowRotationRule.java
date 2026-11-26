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
 * Productos de baja rotación:
 * <ul>
 *   <li>LIQUIDAR: sin ventas durante el periodo configurado, o cobertura superior al doble del umbral.</li>
 *   <li>PROMOCION: demanda decreciente con exceso de stock y margen suficiente para un descuento.</li>
 * </ul>
 */
public class LowRotationRule implements BusinessRule {

    @Override
    public String nombre() {
        return "Baja rotación: liquidación y promociones";
    }

    @Override
    public List<Recomendacion> evaluar(AnalysisSnapshot s) {
        List<Recomendacion> out = new ArrayList<>();
        double umbral = s.settings().mesesSobreinventario();
        double margenMin = s.settings().margenMinimo();
        for (ProductMetrics m : s.productos()) {
            if (m.stock() <= 0) {
                continue;
            }
            double costo = m.producto().costo();
            double precio = m.producto().precioVenta();
            boolean sinMovimiento = m.estado() == EstadoInventario.SIN_MOVIMIENTO;
            boolean coberturaExtrema = m.coberturaMeses() > umbral * 2;
            if (sinMovimiento || coberturaExtrema) {
                double descuento = sinMovimiento ? 0.30 : 0.20;
                double precioLiq = precio * (1 - descuento);
                double unidades = sinMovimiento ? m.stock()
                        : Math.max(0, m.stock() - Math.ceil(m.demandaMensual() * umbral));
                if (unidades <= 0) {
                    continue;
                }
                String problema = sinMovimiento
                        ? (m.diasSinVenta() == null ? "Producto con stock y sin ventas registradas."
                        : "Sin ventas desde hace " + m.diasSinVenta() + " días con " + m.stock() + " u en stock.")
                        : String.format("Cobertura de %.1f meses: rotación muy baja.", m.coberturaMeses());
                out.add(BusinessRule.crear(TipoRecomendacion.LIQUIDAR, sinMovimiento ? Prioridad.ALTA : Prioridad.MEDIA,
                        m, null, null, problema,
                        String.format("Liquidar %.0f u con un descuento de hasta %.0f%% (precio sugerido %s).",
                                unidades, descuento * 100, Formatters.money(precioLiq)),
                        "Recupera capital inmovilizado de " + Formatters.money(unidades * costo)
                                + " y libera espacio de almacenamiento."
                                + (precioLiq < costo ? " El precio de liquidación queda bajo el costo: "
                                + "evalúe la pérdida frente al costo de mantener el stock." : ""),
                        unidades * Math.min(precioLiq, costo)));
                continue;
            }
            ForecastResult fr = m.forecast();
            if (fr != null && fr.tendencia() == ForecastResult.Tendencia.DECRECIENTE
                    && m.coberturaMeses() > umbral * 0.75 && m.margen() > margenMin + 0.05) {
                double descuento = Math.min(0.15, m.margen() - margenMin);
                double exceso = Math.max(0, m.stock() - Math.ceil(m.demandaMensual() * s.settings().horizonteMeses()));
                double contribucion = exceso * (precio * (1 - descuento) - costo);
                out.add(BusinessRule.crear(TipoRecomendacion.PROMOCION, Prioridad.MEDIA, m, null, null,
                        String.format("Ventas en descenso (%.1f u/mes) y %.1f meses de cobertura.",
                                fr.pendiente(), m.coberturaMeses()),
                        String.format("Aplicar una promoción de %.0f%% (precio %s) para acelerar la rotación.",
                                descuento * 100, Formatters.money(precio * (1 - descuento))),
                        String.format("El margen actual (%.1f%%) permite el descuento manteniendo el margen mínimo "
                                + "(%.1f%%).", m.margen() * 100, margenMin * 100),
                        contribucion));
            }
        }
        return out;
    }
}
