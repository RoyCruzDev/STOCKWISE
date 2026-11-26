package com.stockwise.analytics;

import com.stockwise.model.InventarioItem;
import com.stockwise.model.Sucursal;
import com.stockwise.service.AppSettings;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fotografía analítica del negocio a la fecha de corte (última venta registrada).
 * Se calcula una vez y la usan el dashboard, las recomendaciones, el asesor y los reportes.
 *
 * @param unidades90PorProductoSucursal clave {@link #key(long, long)} -> unidades vendidas en los últimos 90 días
 */
public record AnalysisSnapshot(LocalDate fechaCorte, YearMonth ultimoMesCompleto, AppSettings settings,
                               List<ProductMetrics> productos, List<Sucursal> sucursales,
                               List<InventarioItem> inventario, Map<String, Double> unidades90PorProductoSucursal,
                               List<String> notas, Map<Long, SupplyInfo> suministro) {

    /** Compatibilidad: fotografía sin datos de abastecimiento (sin stock en tránsito ni ciclos de proveedor). */
    public AnalysisSnapshot(LocalDate fechaCorte, YearMonth ultimoMesCompleto, AppSettings settings,
                            List<ProductMetrics> productos, List<Sucursal> sucursales, List<InventarioItem> inventario,
                            Map<String, Double> unidades90PorProductoSucursal, List<String> notas) {
        this(fechaCorte, ultimoMesCompleto, settings, productos, sucursales, inventario, unidades90PorProductoSucursal,
                notas, Map.of());
    }

    public SupplyInfo suministro(long productoId) {
        SupplyInfo s = suministro == null ? null : suministro.get(productoId);
        return s == null ? SupplyInfo.vacio(productoId) : s;
    }

    public static String key(long productoId, long sucursalId) {
        return productoId + ":" + sucursalId;
    }

    public double unidades90(long productoId, long sucursalId) {
        return unidades90PorProductoSucursal.getOrDefault(key(productoId, sucursalId), 0.0);
    }

    public Map<Long, ProductMetrics> porId() {
        return productos.stream().collect(Collectors.toMap(ProductMetrics::productoId, Function.identity()));
    }

    public Optional<ProductMetrics> metrics(long productoId) {
        return productos.stream().filter(m -> m.productoId() == productoId).findFirst();
    }

    public List<ProductMetrics> conEstado(EstadoInventario estado) {
        return productos.stream().filter(m -> m.estado() == estado).toList();
    }
}
