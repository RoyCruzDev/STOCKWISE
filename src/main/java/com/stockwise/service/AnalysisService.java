package com.stockwise.service;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.MetricsCalculator;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.analytics.SupplyInfo;
import com.stockwise.model.Proveedor;
import com.stockwise.repository.CompraRepository;
import com.stockwise.repository.ProveedorRepository;
import com.stockwise.model.Producto;
import com.stockwise.prediction.ForecastEngine;
import com.stockwise.prediction.ForecastResult;
import com.stockwise.repository.InventarioRepository;
import com.stockwise.repository.ProductoRepository;
import com.stockwise.repository.SucursalRepository;
import com.stockwise.repository.VentaRepository;
import com.stockwise.util.Formatters;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Construye la fotografía analítica del negocio a partir de los datos almacenados. */
public class AnalysisService {

    private final ProductoRepository productos;
    private final VentaRepository ventas;
    private final InventarioRepository inventario;
    private final SucursalRepository sucursales;
    private final ConfigService config;
    private final CompraRepository compras;
    private final ProveedorRepository proveedores;

    public AnalysisService(ProductoRepository productos, VentaRepository ventas, InventarioRepository inventario,
                           SucursalRepository sucursales, ConfigService config) {
        this(productos, ventas, inventario, sucursales, config, null, null);
    }

    /**
     * @param compras     fuente del stock en tránsito y de los tiempos de entrega observados (puede ser nula)
     * @param proveedores fuente de los tiempos de reposición y ciclos de compra por proveedor (puede ser nula)
     */
    public AnalysisService(ProductoRepository productos, VentaRepository ventas, InventarioRepository inventario,
                           SucursalRepository sucursales, ConfigService config, CompraRepository compras,
                           ProveedorRepository proveedores) {
        this.productos = productos;
        this.ventas = ventas;
        this.inventario = inventario;
        this.sucursales = sucursales;
        this.config = config;
        this.compras = compras;
        this.proveedores = proveedores;
    }

    /** Mínimo de pedidos recibidos para usar el tiempo de entrega observado de un proveedor. */
    public static final int MIN_OBSERVACIONES_LEAD_TIME = 3;

    public ForecastEngine engine() {
        AppSettings s = config.get();
        return new ForecastEngine(new ForecastEngine.Params(s.ventanaPromedioMovil(), s.ventanaPonderado(),
                s.alpha(), s.mesesMinimosConfiables()));
    }

    /** Fecha de corte: última venta registrada (o hoy si no hay ventas). */
    public LocalDate fechaCorte() {
        return ventas.fechaMaxima().orElse(LocalDate.now());
    }

    /**
     * Último mes completo de datos. Si la fecha de corte está a mitad de mes, ese mes se excluye de la serie
     * de predicción para no subestimar la demanda con un mes parcial.
     */
    public static YearMonth ultimoMesCompleto(LocalDate corte) {
        YearMonth ym = YearMonth.from(corte);
        return corte.getDayOfMonth() >= corte.lengthOfMonth() - 2 ? ym : ym.minusMonths(1);
    }

    public AnalysisSnapshot snapshot() {
        return snapshot((hecho, total) -> { });
    }

    /** Avance por producto evaluado; puede lanzar {@link java.util.concurrent.CancellationException}. */
    @FunctionalInterface
    public interface AvanceProductos {
        void avance(int hecho, int total);
    }

    /** Fotografía analítica informando el avance de la evaluación de modelos producto a producto. */
    public AnalysisSnapshot snapshot(AvanceProductos avance) {
        AppSettings s = config.get();
        LocalDate corte = fechaCorte();
        YearMonth ultimoMes = ultimoMesCompleto(corte);
        ForecastEngine engine = engine();
        MetricsCalculator.Params params = new MetricsCalculator.Params(s.stockMinimoDefecto(),
                s.leadTimeDefectoDias(), s.mesesSobreinventario(), s.diasSinMovimiento());

        Map<Long, Map<String, Double>> mensual = new HashMap<>();
        for (VentaRepository.ProductMonth pm : ventas.mensualPorProducto()) {
            mensual.computeIfAbsent(pm.productoId(), k -> new HashMap<>()).put(pm.periodo(), pm.unidades());
        }
        Map<Long, VentaRepository.ProductSummary> resumen = new HashMap<>();
        for (VentaRepository.ProductSummary ps : ventas.resumenPorProducto(corte)) {
            resumen.put(ps.productoId(), ps);
        }
        Map<Long, Integer> stock = inventario.stockTotalPorProducto();

        Map<Long, Proveedor> provs = new HashMap<>();
        if (proveedores != null) {
            proveedores.findAll().forEach(pv -> provs.put(pv.id(), pv));
        }
        Map<Long, CompraRepository.LeadTimeObservado> observados = compras == null ? Map.of()
                : compras.leadTimesObservados();
        Map<Long, Integer> transito = compras == null ? Map.of() : compras.enTransitoPorProducto();
        Map<Long, LocalDate> ultimoPedido = compras == null ? Map.of() : compras.ultimoPedidoPorProveedor();

        List<ProductMetrics> metrics = new ArrayList<>();
        Map<Long, SupplyInfo> suministro = new HashMap<>();
        List<Producto> activos = productos.findActivos();
        int hechos = 0;
        avance.avance(0, activos.size());
        for (Producto p : activos) {
            VentaRepository.ProductSummary r = resumen.get(p.id());
            MetricsCalculator.SalesData data = new MetricsCalculator.SalesData(
                    mensual.getOrDefault(p.id(), Map.of()),
                    r == null ? 0 : r.u30(), r == null ? 0 : r.u90(), r == null ? 0 : r.u365(),
                    r == null ? 0 : r.importe365(), r == null ? null : r.ultimaVenta());
            Proveedor pv = p.proveedorId() == null ? null : provs.get(p.proveedorId());
            CompraRepository.LeadTimeObservado obs = p.proveedorId() == null ? null : observados.get(p.proveedorId());
            boolean obsValido = obs != null && obs.observaciones() >= MIN_OBSERVACIONES_LEAD_TIME;
            Integer ltProveedor = null;
            String origen;
            if (p.leadTimeDias() != null && p.leadTimeDias() > 0) {
                origen = "Producto";
            } else if (pv != null && pv.leadTimeDias() != null && pv.leadTimeDias() > 0) {
                ltProveedor = pv.leadTimeDias();
                origen = "Proveedor (configurado)";
            } else if (obsValido) {
                ltProveedor = (int) Math.round(obs.promedioDias());
                origen = "Proveedor (observado en " + obs.observaciones() + " pedidos)";
            } else {
                origen = "Valor por defecto";
            }
            metrics.add(MetricsCalculator.calcular(p, stock.getOrDefault(p.id(), 0), data, engine, ultimoMes,
                    corte, params, ltProveedor));
            avance.avance(++hechos, activos.size());
            Integer ciclo = pv == null ? null : pv.cicloCompraDias();
            LocalDate ultimo = pv == null ? null : ultimoPedido.get(pv.id());
            suministro.put(p.id(), new SupplyInfo(p.id(), p.proveedorId(), p.proveedor(),
                    transito.getOrDefault(p.id(), 0), ciclo, obsValido ? obs.desviacionDias() : 0, origen,
                    ciclo != null && ciclo > 0 && ultimo != null ? ultimo.plusDays(ciclo) : null));
        }

        Map<String, Double> u90 = new HashMap<>();
        for (VentaRepository.ProductBranchUnits pb : ventas.unidadesPorProductoSucursal(corte.minusDays(90), corte)) {
            u90.put(AnalysisSnapshot.key(pb.productoId(), pb.sucursalId()), pb.unidades());
        }

        List<String> notas = new ArrayList<>();
        if (ventas.count() == 0) {
            notas.add("No hay ventas registradas. Importe el historial de ventas para obtener análisis y predicciones.");
        } else {
            notas.add("Fecha de corte de los datos: " + Formatters.date(corte) + ". Último mes completo usado para "
                    + "predecir: " + Formatters.period(ultimoMes.toString()) + ".");
        }
        int enTransito = suministro.values().stream().mapToInt(SupplyInfo::enTransito).sum();
        if (enTransito > 0) {
            notas.add(enTransito + " unidad(es) en órdenes de compra pendientes se descuentan de las necesidades "
                    + "de compra (stock en tránsito).");
        }
        return new AnalysisSnapshot(corte, ultimoMes, s, metrics, sucursales.findAll(), inventario.findAll(), u90,
                notas, suministro);
    }

    /** Predicción de la serie total de ventas (unidades o importe) de la empresa o de una sucursal. */
    public ForecastResult pronosticoGlobal(Long sucursalId, boolean importe) {
        return pronosticoSerie(null, sucursalId, importe);
    }

    /** Predicción de las unidades vendidas de un producto. */
    public ForecastResult pronosticoProducto(long productoId) {
        return pronosticoSerie(productoId, null, false);
    }

    private ForecastResult pronosticoSerie(Long productoId, Long sucursalId, boolean importe) {
        LocalDate corte = fechaCorte();
        YearMonth fin = ultimoMesCompleto(corte);
        Map<String, Double> map = new HashMap<>();
        for (VentaRepository.MonthlyTotal mt : ventas.mensual(productoId, sucursalId)) {
            map.put(mt.periodo(), importe ? mt.importe() : mt.unidades());
        }
        List<String> periodos = MetricsCalculator.periodos(map, fin);
        return engine().pronosticar(periodos, MetricsCalculator.serie(map, periodos));
    }

    public Optional<ProductMetrics> metricsProducto(long productoId) {
        return snapshot().metrics(productoId);
    }
}
