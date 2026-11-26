package com.stockwise.service;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.StockReconstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scorecard de calidad del análisis: cada componente se puntúa de 0 a 100 solo si hay datos para calcularlo; los
 * componentes sin datos se informan como "no disponible" y no cuentan en el puntaje global.
 */
public class ScorecardService {

    /** Componente del scorecard. {@code puntaje} nulo = no disponible. */
    public record Componente(String nombre, Double puntaje, double peso, String detalle) {
    }

    public record Scorecard(Double puntaje, String calificacion, List<Componente> componentes, PlanningService.Roi roi,
                            BacktestService.Resultado backtest, List<String> notas) {
    }

    private final PlanningService planning;
    private final BacktestService backtest;
    private final StockHistoryService stockHistorico;

    public ScorecardService(PlanningService planning, BacktestService backtest, StockHistoryService stockHistorico) {
        this.planning = planning;
        this.backtest = backtest;
        this.stockHistorico = stockHistorico;
    }

    /** Calcula el scorecard con un backtest de los últimos 3 meses a 1 mes. */
    public Scorecard calcular() {
        PlanningService.Analisis a = planning.analizar();
        BacktestService.Resultado bt = backtest.ejecutar(3, 1);
        AnalysisSnapshot s = a.snapshot();
        // Sin compras/entradas registradas la disponibilidad no es medible: el componente queda "no disponible".
        Map<Long, StockReconstructor.Resumen> disp = stockHistorico.disponibilidadMedible()
                ? stockHistorico.resumenPorProducto(s.fechaCorte().minusDays(89), s.fechaCorte()) : null;
        return construir(s, bt, disp, PlanningService.roi(a.plan(), s.productos()));
    }

    public static Scorecard construir(AnalysisSnapshot s, BacktestService.Resultado bt,
                                      Map<Long, StockReconstructor.Resumen> disp, PlanningService.Roi roi) {
        List<Componente> comp = new ArrayList<>();
        List<ProductMetrics> ps = s.productos();
        int mesesMin = s.settings().mesesMinimosConfiables();

        // 1. Cobertura de datos de ventas
        if (ps.isEmpty()) {
            comp.add(new Componente("Cobertura de historial", null, 0.2, "No disponible: no hay productos activos."));
        } else {
            long conHist = ps.stream().filter(m -> m.forecast() != null && m.forecast().historia().length >= mesesMin)
                    .count();
            comp.add(new Componente("Cobertura de historial", 100.0 * conHist / ps.size(), 0.2,
                    conHist + " de " + ps.size() + " productos con al menos " + mesesMin + " meses de historial."));
        }
        // 2. Precisión del pronóstico (backtest)
        if (bt == null || !bt.disponible() || bt.precision() == null) {
            comp.add(new Componente("Precisión del pronóstico", null, 0.3,
                    "No disponible: no hay productos con historial suficiente para el backtest."));
        } else {
            comp.add(new Componente("Precisión del pronóstico", 100 * bt.precision(), 0.3,
                    String.format("WAPE %.1f%% en backtest walk-forward (%d productos, últimos %d meses).",
                            bt.wape() * 100, bt.productos().size(), bt.mesesPrueba())));
        }
        // 3. Sesgo
        if (bt == null || bt.sesgo() == null) {
            comp.add(new Componente("Sesgo del pronóstico", null, 0.1, "No disponible: requiere backtest."));
        } else {
            comp.add(new Componente("Sesgo del pronóstico", 100 * Math.max(0, 1 - Math.abs(bt.sesgo())), 0.1,
                    String.format("%s %.1f%% respecto de las ventas reales.", bt.sesgo() >= 0 ? "Sobreestima"
                            : "Subestima", Math.abs(bt.sesgo()) * 100)));
        }
        // 4. Calidad de datos maestros
        if (ps.isEmpty()) {
            comp.add(new Componente("Calidad de datos maestros", null, 0.15, "No disponible."));
        } else {
            long completos = ps.stream().filter(m -> m.producto().costo() > 0 && m.producto().precioVenta() > 0
                    && m.producto().precioVenta() >= m.producto().costo()).count();
            comp.add(new Componente("Calidad de datos maestros", 100.0 * completos / ps.size(), 0.15,
                    (ps.size() - completos) + " producto(s) sin costo/precio o con precio menor que el costo."));
        }
        // 5. Salud del inventario
        List<ProductMetrics> conStock = ps.stream().filter(m -> m.stock() > 0 || m.estado() == EstadoInventario.AGOTADO)
                .toList();
        if (conStock.isEmpty()) {
            comp.add(new Componente("Salud del inventario", null, 0.1, "No disponible: no hay inventario registrado."));
        } else {
            long sanos = conStock.stream().filter(m -> m.estado() == EstadoInventario.NORMAL).count();
            comp.add(new Componente("Salud del inventario", 100.0 * sanos / conStock.size(), 0.1,
                    sanos + " de " + conStock.size() + " productos en estado normal (sin quiebre, riesgo ni exceso)."));
        }
        // 6. Disponibilidad real (stock reconstruido)
        int dias = 0;
        int sin = 0;
        int inconsistentes = 0;
        if (disp != null) {
            for (StockReconstructor.Resumen r : disp.values()) {
                dias += r.dias();
                sin += r.diasSinStock();
                if (!r.consistente()) {
                    inconsistentes++;
                }
            }
        }
        if (dias == 0) {
            comp.add(new Componente("Disponibilidad (nivel de servicio real)", null, 0.15, disp == null
                    ? "No disponible: no hay compras ni entradas registradas para detectar quiebres de stock."
                    : "No disponible: no hay movimientos para reconstruir el stock de los últimos 90 días."));
        } else {
            comp.add(new Componente("Disponibilidad (nivel de servicio real)", 100.0 * (dias - sin) / dias, 0.15,
                    String.format("Días-producto con stock en los 90 días previos al corte: %.1f%%.%s",
                            100.0 * (dias - sin) / dias, inconsistentes > 0 ? " " + inconsistentes
                                    + " producto(s) con stock reconstruido negativo (faltan compras registradas)." : "")));
        }

        double suma = 0;
        double pesos = 0;
        for (Componente c : comp) {
            if (c.puntaje() != null) {
                suma += c.puntaje() * c.peso();
                pesos += c.peso();
            }
        }
        Double total = pesos > 0 ? suma / pesos : null;
        List<String> notas = new ArrayList<>();
        long noDisp = comp.stream().filter(c -> c.puntaje() == null).count();
        if (noDisp > 0) {
            notas.add(noDisp + " componente(s) no disponibles por falta de datos: el puntaje global se calcula solo con "
                    + "los componentes disponibles.");
        }
        return new Scorecard(total, calificacion(total), comp, roi, bt, notas);
    }

    static String calificacion(Double p) {
        if (p == null) {
            return "No disponible";
        }
        return p >= 85 ? "A - Excelente" : p >= 70 ? "B - Buena" : p >= 55 ? "C - Regular" : "D - Deficiente";
    }
}
