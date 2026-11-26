package com.stockwise.service;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.EstadoInventario;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.exporter.ExcelExporter;
import com.stockwise.exporter.PdfExporter;
import com.stockwise.exporter.ReportTable;
import com.stockwise.model.Recomendacion;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.recommendation.PurchasePlan;
import com.stockwise.recommendation.PurchaseRecommendation;
import com.stockwise.repository.VentaRepository;
import com.stockwise.util.Formatters;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static com.stockwise.exporter.ReportTable.ColumnType.DECIMAL;
import static com.stockwise.exporter.ReportTable.ColumnType.ENTERO;
import static com.stockwise.exporter.ReportTable.ColumnType.MONEDA;
import static com.stockwise.exporter.ReportTable.ColumnType.PORCENTAJE;
import static com.stockwise.exporter.ReportTable.ColumnType.TEXTO;

/** Genera los reportes del sistema como tablas exportables a Excel y PDF. */
public class ReportService {

    public enum ReportType {
        VENTAS("Ventas históricas"),
        PREDICCION("Predicción de demanda"),
        INVENTARIO("Inventario"),
        MAS_VENDIDOS("Productos más vendidos"),
        BAJA_ROTACION("Productos de baja rotación"),
        COMPRAS("Recomendaciones de compra"),
        RENTABILIDAD("Rentabilidad por producto"),
        SUCURSALES("Comparación entre sucursales"),
        ASESOR("Recomendaciones estratégicas");

        private final String etiqueta;

        ReportType(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }

        /** Indica si el reporte usa el rango de fechas. */
        public boolean usaRango() {
            return this == VENTAS || this == MAS_VENDIDOS || this == RENTABILIDAD || this == SUCURSALES;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    private final VentaRepository ventas;
    private final AnalysisService analysis;
    private final RecommendationService recomendaciones;
    private final PresupuestoService presupuesto;
    private final SucursalService sucursales;
    private final ConfigService config;

    public ReportService(VentaRepository ventas, AnalysisService analysis, RecommendationService recomendaciones,
                         PresupuestoService presupuesto, SucursalService sucursales, ConfigService config) {
        this.ventas = ventas;
        this.analysis = analysis;
        this.recomendaciones = recomendaciones;
        this.presupuesto = presupuesto;
        this.sucursales = sucursales;
        this.config = config;
    }

    public ReportTable generar(ReportType tipo, LocalDate desde, LocalDate hasta) {
        if (tipo.usaRango() && (desde == null || hasta == null || desde.isAfter(hasta))) {
            throw new ValidationException("Seleccione un rango de fechas válido.");
        }
        String rango = tipo.usaRango() ? "Del " + Formatters.date(desde) + " al " + Formatters.date(hasta)
                : "Fecha de corte: " + Formatters.date(analysis.fechaCorte());
        return switch (tipo) {
            case VENTAS -> ventas(rango, desde, hasta);
            case PREDICCION -> prediccion(rango);
            case INVENTARIO -> inventario(rango);
            case MAS_VENDIDOS -> masVendidos(rango, desde, hasta);
            case BAJA_ROTACION -> bajaRotacion(rango);
            case COMPRAS -> compras(rango);
            case RENTABILIDAD -> rentabilidad(rango, desde, hasta);
            case SUCURSALES -> sucursales(rango, desde, hasta);
            case ASESOR -> asesor(rango);
        };
    }

    public void exportarExcel(List<ReportTable> tablas, Path destino) throws IOException {
        AppSettings s = config.get();
        ExcelExporter.exportar(tablas, destino, s.empresa(), s.simboloMoneda());
    }

    public void exportarPdf(List<ReportTable> tablas, Path destino) throws IOException {
        PdfExporter.exportar(tablas, destino, config.get().empresa());
    }

    // ------------------------------------------------------------------ reportes

    private ReportTable ventas(String rango, LocalDate desde, LocalDate hasta) {
        List<List<Object>> filas = new ArrayList<>();
        double totU = 0, totI = 0, totC = 0;
        for (VentaRepository.Aggregate a : ventas.mensualConCosto(desde, hasta)) {
            filas.add(List.of(Formatters.period(a.codigo()), a.unidades(), a.importe(), a.costo(), a.utilidad(),
                    a.importe() > 0 ? a.utilidad() / a.importe() : 0.0));
            totU += a.unidades();
            totI += a.importe();
            totC += a.costo();
        }
        List<String> resumen = List.of("Unidades vendidas: " + Formatters.integer(totU) + "   ·   Ventas: "
                + Formatters.money(totI) + "   ·   Utilidad bruta: " + Formatters.money(totI - totC));
        return new ReportTable("Ventas históricas", rango,
                List.of("Periodo", "Unidades", "Ventas", "Costo de ventas", "Utilidad bruta", "Margen"),
                List.of(TEXTO, ENTERO, MONEDA, MONEDA, MONEDA, PORCENTAJE), filas, resumen);
    }

    private ReportTable prediccion(String rango) {
        AnalysisSnapshot s = analysis.snapshot();
        List<List<Object>> filas = new ArrayList<>();
        for (ProductMetrics m : s.productos()) {
            ForecastResult fr = m.forecast();
            boolean ok = fr.suficiente();
            filas.add(Arrays.asList(m.producto().codigo(), m.producto().nombre(), fr.historia().length,
                    fr.tendencia().toString(), ok ? fr.mejor().metodo() : "—",
                    ok ? fr.mejor().mae() : null, ok ? fr.mejor().mape() : null, fr.confianza().toString(),
                    ok ? fr.demandaProximos(1) : null, ok ? fr.demandaProximos(3) : null,
                    ok ? fr.demandaProximos(6) : null));
        }
        long insuf = s.productos().stream().filter(m -> !m.forecast().suficiente()).count();
        return new ReportTable("Predicción de demanda", rango,
                List.of("Código", "Producto", "Meses hist.", "Tendencia", "Mejor método", "MAE", "MAPE", "Confianza",
                        "Próx. 1 mes", "Próx. 3 meses", "Próx. 6 meses"),
                List.of(TEXTO, TEXTO, ENTERO, TEXTO, TEXTO, DECIMAL, PORCENTAJE, TEXTO, DECIMAL, DECIMAL, DECIMAL),
                filas, insuf == 0 ? s.notas()
                : concat(s.notas(), insuf + " producto(s) sin historial suficiente: no se generó pronóstico."));
    }

    private ReportTable inventario(String rango) {
        AnalysisSnapshot s = analysis.snapshot();
        List<List<Object>> filas = new ArrayList<>();
        double valor = 0;
        for (ProductMetrics m : s.productos()) {
            filas.add(Arrays.asList(m.producto().codigo(), m.producto().nombre(), m.stock(), m.stockMinimo(),
                    m.stockMaximo(), m.producto().costo(), m.valorInventario(), cobertura(m), m.rotacionAnual(),
                    m.estado().etiqueta()));
            valor += m.valorInventario();
        }
        return new ReportTable("Inventario", rango,
                List.of("Código", "Producto", "Stock", "Mínimo", "Máximo", "Costo", "Valor", "Cobertura (días)",
                        "Rotación anual", "Estado"),
                List.of(TEXTO, TEXTO, ENTERO, ENTERO, ENTERO, MONEDA, MONEDA, DECIMAL, DECIMAL, TEXTO), filas,
                List.of("Valor total del inventario (a costo): " + Formatters.money(valor),
                        "Agotados: " + s.conEstado(EstadoInventario.AGOTADO).size() + "   ·   En riesgo: "
                                + s.conEstado(EstadoInventario.RIESGO_AGOTAMIENTO).size() + "   ·   Sobreinventario: "
                                + s.conEstado(EstadoInventario.SOBREINVENTARIO).size() + "   ·   Sin movimiento: "
                                + s.conEstado(EstadoInventario.SIN_MOVIMIENTO).size()));
    }

    private ReportTable masVendidos(String rango, LocalDate desde, LocalDate hasta) {
        List<VentaRepository.Aggregate> agg = ventas.porProducto(desde, hasta).stream()
                .filter(a -> a.unidades() > 0)
                .sorted(Comparator.comparingDouble(VentaRepository.Aggregate::unidades).reversed()).limit(30).toList();
        double total = ventas.totalImporte(desde, hasta);
        List<List<Object>> filas = new ArrayList<>();
        int i = 1;
        for (VentaRepository.Aggregate a : agg) {
            filas.add(List.of(i++, a.codigo(), a.nombre(), a.unidades(), a.importe(),
                    total > 0 ? a.importe() / total : 0.0));
        }
        return new ReportTable("Productos más vendidos", rango,
                List.of("#", "Código", "Producto", "Unidades", "Ventas", "Participación"),
                List.of(ENTERO, TEXTO, TEXTO, ENTERO, MONEDA, PORCENTAJE), filas, List.of());
    }

    private ReportTable bajaRotacion(String rango) {
        AnalysisSnapshot s = analysis.snapshot();
        List<ProductMetrics> lista = s.productos().stream()
                .filter(m -> m.stock() > 0 && (m.estado() == EstadoInventario.SIN_MOVIMIENTO
                        || m.estado() == EstadoInventario.SOBREINVENTARIO))
                .sorted(Comparator.comparingDouble(ProductMetrics::valorInventario).reversed()).toList();
        List<List<Object>> filas = new ArrayList<>();
        double inmovilizado = 0;
        for (ProductMetrics m : lista) {
            filas.add(Arrays.asList(m.producto().codigo(), m.producto().nombre(), m.stock(), m.unidades90(),
                    m.diasSinVenta(), cobertura(m), m.rotacionAnual(), m.valorInventario(), m.estado().etiqueta()));
            inmovilizado += m.valorInventario();
        }
        return new ReportTable("Productos de baja rotación", rango,
                List.of("Código", "Producto", "Stock", "Vendido 90 días", "Días sin venta", "Cobertura (días)",
                        "Rotación anual", "Valor inmovilizado", "Estado"),
                List.of(TEXTO, TEXTO, ENTERO, ENTERO, ENTERO, DECIMAL, DECIMAL, MONEDA, TEXTO), filas,
                List.of("Capital inmovilizado en productos de baja rotación: " + Formatters.money(inmovilizado)));
    }

    private ReportTable compras(String rango) {
        PresupuestoService.EstadoPresupuesto ep = presupuesto.estadoActual();
        PurchasePlan plan = recomendaciones.planDeCompras(analysis.snapshot(), ep.disponible());
        List<List<Object>> filas = new ArrayList<>();
        for (PurchaseRecommendation r : plan.lineas()) {
            if (r.decision() == PurchaseRecommendation.Decision.NO_REQUIERE) {
                continue;
            }
            filas.add(List.of(r.metrics().producto().codigo(), r.metrics().producto().nombre(), r.metrics().stock(),
                    r.metrics().demandaMensual(), r.cantidadSugerida(), r.cantidadAprobada(), r.costoUnitario(),
                    r.costoTotal(), r.prioridad().etiqueta(), r.decision().toString()));
        }
        List<String> resumen = new ArrayList<>(List.of("Presupuesto disponible: " + Formatters.money(ep.disponible())
                + " (" + ep.origen() + ")   ·   Compra aprobada: " + Formatters.money(plan.totalAprobado())
                + "   ·   Saldo: " + Formatters.money(plan.saldo()),
                "Productos que no requieren compra: " + plan.noRequieren().size()));
        resumen.addAll(plan.notas());
        return new ReportTable("Recomendaciones de compra", rango,
                List.of("Código", "Producto", "Stock", "Demanda/mes", "Sugerido", "Aprobado", "Costo unit.",
                        "Inversión", "Prioridad", "Decisión"),
                List.of(TEXTO, TEXTO, ENTERO, DECIMAL, ENTERO, ENTERO, MONEDA, MONEDA, TEXTO, TEXTO), filas, resumen);
    }

    private ReportTable rentabilidad(String rango, LocalDate desde, LocalDate hasta) {
        List<VentaRepository.Aggregate> agg = ventas.porProducto(desde, hasta).stream()
                .filter(a -> a.importe() > 0)
                .sorted(Comparator.comparingDouble(VentaRepository.Aggregate::utilidad).reversed()).toList();
        double utilTotal = agg.stream().mapToDouble(VentaRepository.Aggregate::utilidad).sum();
        double ventasTotal = agg.stream().mapToDouble(VentaRepository.Aggregate::importe).sum();
        List<List<Object>> filas = new ArrayList<>();
        double acumulada = 0;
        for (VentaRepository.Aggregate a : agg) {
            acumulada += a.utilidad();
            String clase = utilTotal <= 0 ? "-" : acumulada <= utilTotal * 0.8 ? "A" : acumulada <= utilTotal * 0.95
                    ? "B" : "C";
            filas.add(List.of(a.codigo(), a.nombre(), a.unidades(), a.importe(), a.costo(), a.utilidad(),
                    a.importe() > 0 ? a.utilidad() / a.importe() : 0.0, utilTotal > 0 ? a.utilidad() / utilTotal : 0.0,
                    clase));
        }
        return new ReportTable("Rentabilidad por producto", rango,
                List.of("Código", "Producto", "Unidades", "Ventas", "Costo", "Utilidad", "Margen", "% Utilidad",
                        "Clase ABC"),
                List.of(TEXTO, TEXTO, ENTERO, MONEDA, MONEDA, MONEDA, PORCENTAJE, PORCENTAJE, TEXTO), filas,
                List.of("Ventas: " + Formatters.money(ventasTotal) + "   ·   Utilidad bruta: " + Formatters.money(utilTotal)
                        + "   ·   Margen: " + Formatters.percent(ventasTotal > 0 ? utilTotal / ventasTotal : 0),
                        "Clase A: productos que generan el 80% de la utilidad; B: siguiente 15%; C: 5% restante."));
    }

    private ReportTable sucursales(String rango, LocalDate desde, LocalDate hasta) {
        List<List<Object>> filas = new ArrayList<>();
        for (SucursalService.RendimientoSucursal r : sucursales.comparar(desde, hasta)) {
            filas.add(Arrays.asList(r.sucursal().codigo(), r.sucursal().nombre(), r.unidades(), r.ventas(),
                    r.utilidad(), r.margen(), r.participacion(), r.valorInventario(), r.rotacion()));
        }
        return new ReportTable("Comparación entre sucursales", rango,
                List.of("Código", "Sucursal", "Unidades", "Ventas", "Utilidad", "Margen", "Participación",
                        "Inventario (costo)", "Rotación anual"),
                List.of(TEXTO, TEXTO, ENTERO, MONEDA, MONEDA, PORCENTAJE, PORCENTAJE, MONEDA, DECIMAL), filas, List.of());
    }

    private ReportTable asesor(String rango) {
        List<List<Object>> filas = new ArrayList<>();
        for (Recomendacion r : recomendaciones.recomendaciones()) {
            filas.add(Arrays.asList(r.prioridad().etiqueta(), r.tipo().etiqueta(), r.producto(),
                    r.sucursal() == null ? "" : r.sucursal() + (r.sucursalDestino() == null ? "" : " → " + r.sucursalDestino()),
                    r.problema(), r.accion(), r.beneficio(), r.estado()));
        }
        return new ReportTable("Recomendaciones estratégicas", rango,
                List.of("Prioridad", "Tipo", "Producto", "Sucursal", "Problema", "Acción", "Beneficio", "Estado"),
                List.of(TEXTO, TEXTO, TEXTO, TEXTO, TEXTO, TEXTO, MONEDA, TEXTO), filas,
                filas.isEmpty() ? List.of("Genere las recomendaciones desde el módulo Asesor estratégico.") : List.of());
    }

    private static Double cobertura(ProductMetrics m) {
        return Double.isInfinite(m.coberturaDias()) ? null : m.coberturaDias();
    }

    private static List<String> concat(List<String> a, String b) {
        List<String> out = new ArrayList<>(a);
        out.add(b);
        return out;
    }
}
