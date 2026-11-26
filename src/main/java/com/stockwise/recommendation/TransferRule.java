package com.stockwise.recommendation;

import com.stockwise.analytics.AnalysisSnapshot;
import com.stockwise.analytics.ProductMetrics;
import com.stockwise.model.InventarioItem;
import com.stockwise.model.Prioridad;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.Sucursal;
import com.stockwise.model.TipoRecomendacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Detecta productos con exceso en una sucursal y faltante en otra, y propone trasladar unidades.
 * <ul>
 *   <li>Demanda mensual por sucursal = ventas de los últimos 90 días / 3.</li>
 *   <li>Nivel objetivo por sucursal = demanda × (tiempo de reposición + horizonte) en meses.</li>
 *   <li>Exceso = stock − objetivo (si es positivo). Faltante = objetivo − stock cuando la cobertura es menor
 *       que el tiempo de reposición.</li>
 * </ul>
 */
public class TransferRule implements BusinessRule {

    /** Traslado propuesto. */
    public record Traslado(ProductMetrics metrics, Sucursal origen, Sucursal destino, int cantidad,
                           int stockOrigen, int stockDestino, double demandaOrigen, double demandaDestino) {
    }

    @Override
    public String nombre() {
        return "Traslados entre sucursales";
    }

    @Override
    public List<Recomendacion> evaluar(AnalysisSnapshot s) {
        List<Recomendacion> out = new ArrayList<>();
        for (Traslado t : calcular(s)) {
            ProductMetrics m = t.metrics();
            double ahorro = t.cantidad() * m.producto().costo();
            Prioridad prioridad = t.stockDestino() == 0 ? Prioridad.ALTA : Prioridad.MEDIA;
            out.add(BusinessRule.crear(TipoRecomendacion.TRASLADO, prioridad, m, t.origen(), t.destino(),
                    String.format("%s tiene exceso (%d u, demanda %.1f u/mes) mientras %s tiene faltante "
                                    + "(%d u, demanda %.1f u/mes).", t.origen().codigo(), t.stockOrigen(),
                            t.demandaOrigen(), t.destino().codigo(), t.stockDestino(), t.demandaDestino()),
                    "Trasladar " + t.cantidad() + " unidades de " + t.origen().codigo() + " a "
                            + t.destino().codigo() + ".",
                    "El traslado cubre la demanda de " + t.destino().codigo() + " sin realizar una nueva compra y "
                            + "reduce el inventario inmovilizado en " + t.origen().codigo() + ".",
                    ahorro > 0 ? ahorro : null));
        }
        return out;
    }

    public List<Traslado> calcular(AnalysisSnapshot s) {
        Map<Long, Sucursal> sucursales = s.sucursales().stream().filter(Sucursal::activa)
                .collect(Collectors.toMap(Sucursal::id, Function.identity()));
        if (sucursales.size() < 2) {
            return List.of();
        }
        int horizonte = s.settings().horizonteMeses();
        Map<Long, List<InventarioItem>> invPorProducto = s.inventario().stream()
                .collect(Collectors.groupingBy(InventarioItem::productoId));
        List<Traslado> out = new ArrayList<>();
        for (ProductMetrics m : s.productos()) {
            double meses = m.leadTimeDias() / ProductMetrics.DIAS_POR_MES + horizonte;
            List<double[]> excesos = new ArrayList<>(); // [sucursalId, exceso, stock, demanda]
            List<double[]> faltantes = new ArrayList<>();
            for (Sucursal suc : sucursales.values()) {
                int stock = invPorProducto.getOrDefault(m.productoId(), List.of()).stream()
                        .filter(i -> i.sucursalId() == suc.id()).mapToInt(InventarioItem::stock).sum();
                double demanda = s.unidades90(m.productoId(), suc.id()) / 3.0;
                double objetivo = Math.ceil(demanda * meses);
                double coberturaDias = demanda > 0 ? stock / (demanda / ProductMetrics.DIAS_POR_MES)
                        : Double.POSITIVE_INFINITY;
                if (stock > objetivo && stock > 0) {
                    excesos.add(new double[]{suc.id(), stock - objetivo, stock, demanda});
                } else if (demanda > 0 && coberturaDias < m.leadTimeDias()) {
                    faltantes.add(new double[]{suc.id(), objetivo - stock, stock, demanda});
                }
            }
            excesos.sort(Comparator.comparingDouble((double[] a) -> -a[1]));
            faltantes.sort(Comparator.comparingDouble((double[] a) -> -a[1]));
            for (double[] f : faltantes) {
                for (double[] e : excesos) {
                    int cantidad = (int) Math.floor(Math.min(e[1], f[1]));
                    if (cantidad <= 0) {
                        continue;
                    }
                    out.add(new Traslado(m, sucursales.get((long) e[0]), sucursales.get((long) f[0]), cantidad,
                            (int) e[2], (int) f[2], e[3], f[3]));
                    e[1] -= cantidad;
                    f[1] -= cantidad;
                    if (f[1] <= 0) {
                        break;
                    }
                }
            }
        }
        return out;
    }
}
