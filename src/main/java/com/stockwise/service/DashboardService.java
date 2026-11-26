package com.stockwise.service;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.Recomendacion;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.recommendation.StrategicAdvisor;
import com.stockwise.repository.VentaRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/** Calcula todos los indicadores del panel principal en una sola pasada. */
public class DashboardService {

    /** Datos del panel. */
    public record DashboardData(AnalysisSnapshot snapshot, long totalVentasRegistradas, double ventas12m,
                                double utilidad12m, double ventasUltimoMes, double ventasMesAnterior,
                                String ultimoMes, List<VentaRepository.MonthlyTotal> mensual,
                                ForecastResult pronosticoImporte, List<VentaRepository.Aggregate> top,
                                List<VentaRepository.Aggregate> categorias, List<ProductMetrics> riesgo,
                                List<ProductMetrics> sobreinventario, List<ProductMetrics> bajaRotacion,
                                List<Recomendacion> alertas, double valorInventario, long unidadesInventario) {
    }

    /** Resumen del negocio para Inicio (consultas agregadas, sin calcular pronósticos). */
    public record ResumenNegocio(LocalDate corte, double ventas12m, double utilidad12m, double valorInventario,
                                 long unidadesInventario) {
        /** Margen bruto de los últimos 12 meses (nulo sin ventas). */
        public Double margen() {
            return ventas12m > 0 ? utilidad12m / ventas12m : null;
        }
    }

    private final AnalysisService analysis;
    private final VentaRepository ventas;
    private final com.stockwise.repository.InventarioRepository inventario;

    public DashboardService(AnalysisService analysis, VentaRepository ventas) {
        this(analysis, ventas, null);
    }

    public DashboardService(AnalysisService analysis, VentaRepository ventas,
                            com.stockwise.repository.InventarioRepository inventario) {
        this.analysis = analysis;
        this.ventas = ventas;
        this.inventario = inventario;
    }

    /** Ventas y utilidad de los 12 meses hasta la última venta registrada e inventario valorizado al costo. */
    public ResumenNegocio resumenNegocio() {
        LocalDate corte = ventas.fechaMaxima().orElse(null);
        double v = 0;
        double u = 0;
        if (corte != null) {
            for (VentaRepository.Aggregate a : ventas.porProducto(corte.minusMonths(12).plusDays(1), corte)) {
                v += a.importe();
                u += a.utilidad();
            }
        }
        return new ResumenNegocio(corte, v, u, inventario == null ? 0 : inventario.valorTotalCosto(),
                inventario == null ? 0 : inventario.unidadesTotales());
    }

    public DashboardData calcular() {
        AnalysisSnapshot s = analysis.snapshot();
        LocalDate corte = s.fechaCorte();
        LocalDate desde12 = corte.minusMonths(12).plusDays(1);
        List<VentaRepository.Aggregate> porProducto = ventas.porProducto(desde12, corte);
        double ventas12 = porProducto.stream().mapToDouble(VentaRepository.Aggregate::importe).sum();
        double utilidad12 = porProducto.stream().mapToDouble(VentaRepository.Aggregate::utilidad).sum();

        YearMonth ultimo = s.ultimoMesCompleto();
        double ultimoMes = ventas.totalImporte(ultimo.atDay(1), ultimo.atEndOfMonth());
        double anterior = ventas.totalImporte(ultimo.minusMonths(1).atDay(1), ultimo.minusMonths(1).atEndOfMonth());

        List<VentaRepository.MonthlyTotal> mensual = ventas.mensual(null, null).stream()
                .filter(m -> m.periodo().compareTo(ultimo.minusMonths(23).toString()) >= 0)
                // El mes en curso está incompleto: mostrarlo haría parecer que las ventas se desplomaron.
                .filter(m -> m.periodo().compareTo(ultimo.toString()) <= 0).toList();
        List<VentaRepository.Aggregate> top = ventas.porProducto(corte.minusDays(89), corte).stream()
                .filter(a -> a.unidades() > 0)
                .sorted(Comparator.comparingDouble(VentaRepository.Aggregate::importe).reversed()).limit(8).toList();

        List<ProductMetrics> riesgo = s.productos().stream()
                .filter(m -> m.estado() == EstadoInventario.AGOTADO || m.estado() == EstadoInventario.RIESGO_AGOTAMIENTO)
                .sorted(Comparator.comparingDouble(ProductMetrics::coberturaDias)).toList();
        List<ProductMetrics> sobre = s.productos().stream()
                .filter(m -> m.estado() == EstadoInventario.SOBREINVENTARIO)
                .sorted(Comparator.comparingDouble(ProductMetrics::valorInventario).reversed()).toList();
        List<ProductMetrics> baja = s.productos().stream()
                .filter(m -> m.stock() > 0 && m.estado() == EstadoInventario.SIN_MOVIMIENTO)
                .sorted(Comparator.comparingDouble(ProductMetrics::valorInventario).reversed()).toList();

        List<Recomendacion> alertas = new StrategicAdvisor().generar(s).stream().limit(8).toList();
        double valor = s.productos().stream().mapToDouble(ProductMetrics::valorInventario).sum();
        long unidades = s.productos().stream().mapToLong(ProductMetrics::stock).sum();
        return new DashboardData(s, ventas.count(), ventas12, utilidad12, ultimoMes, anterior, ultimo.toString(),
                mensual, analysis.pronosticoGlobal(null, true), top, ventas.porCategoria(desde12, corte), riesgo,
                sobre, baja, alertas, valor, unidades);
    }
}
