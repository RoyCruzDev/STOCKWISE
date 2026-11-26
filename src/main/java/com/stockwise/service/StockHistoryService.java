package com.stockwise.service;

import com.stockwise.analytics.StockReconstructor;
import com.stockwise.analytics.StockReconstructor.DiaStock;
import com.stockwise.database.Database;
import com.stockwise.repository.StockHistoricoRepository;
import com.stockwise.repository.StockHistoricoRepository.Flujo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Reconstruye el stock histórico diario por producto y sucursal (o total del producto) y lo guarda en la tabla
 * {@code stock_historico}. Ver {@link StockReconstructor} para el método de cálculo.
 */
public class StockHistoryService {

    private static final Logger LOG = Logger.getLogger(StockHistoryService.class.getName());

    /** Resultado de la reconstrucción completa. */
    public record ResultadoReconstruccion(int combinaciones, long dias, int inconsistentes, List<String> notas) {
    }

    private final Database db;
    private final StockHistoricoRepository repo;

    public StockHistoryService(Database db, StockHistoricoRepository repo) {
        this.db = db;
        this.repo = repo;
    }

    /** Datos de partida de una combinación: stock actual y flujos diarios. */
    private static final class Serie {
        double stockFinal;
        final NavigableMap<LocalDate, double[]> flujos = new TreeMap<>();

        void sumar(LocalDate f, double entradas, double salidas) {
            double[] v = flujos.computeIfAbsent(f, k -> new double[2]);
            v[0] += entradas;
            v[1] += salidas;
        }
    }

    /** Carga flujos y stock agrupados por clave (porSucursal: "prod:suc"; si no: "prod"). */
    private Map<String, Serie> cargar(Long productoId, boolean porSucursal) {
        Map<String, Serie> series = new HashMap<>();
        List<Flujo> todos = new ArrayList<>();
        todos.addAll(repo.ventasPorDia(productoId));
        todos.addAll(repo.comprasRecibidasPorDia(productoId));
        todos.addAll(repo.otrosMovimientosPorDia(productoId));
        for (Flujo f : todos) {
            series.computeIfAbsent(clave(f.productoId(), f.sucursalId(), porSucursal), k -> new Serie())
                    .sumar(f.fecha(), f.entradas(), f.salidas());
        }
        for (long[] s : repo.stockActual(productoId)) {
            series.computeIfAbsent(clave(s[0], s[1], porSucursal), k -> new Serie()).stockFinal += s[2];
        }
        return series;
    }

    private static String clave(long p, long s, boolean porSucursal) {
        return porSucursal ? p + ":" + s : String.valueOf(p);
    }

    public static final String SIN_ENTRADAS = "No hay compras ni entradas registradas: el stock reconstruido supone que "
            + "no hubo reposiciones, por lo que no permite medir quiebres de stock ni la disponibilidad histórica "
            + "(resultado pendiente). Importe las órdenes de compra recibidas para obtenerla.";

    /**
     * Sin registros de reposición la reconstrucción solo suma ventas hacia atrás: el stock nunca llega a cero y la
     * disponibilidad saldría 100 % de forma trivial. En ese caso los indicadores de disponibilidad no se informan.
     */
    public boolean disponibilidadMedible() {
        return repo.hayEntradas();
    }

    /** Stock diario reconstruido de un producto (total de sucursales o de una sucursal), hasta hoy. */
    public List<DiaStock> reconstruir(long productoId, Long sucursalId) {
        Map<String, Serie> series = cargar(productoId, sucursalId != null);
        Serie s = series.get(sucursalId == null ? String.valueOf(productoId) : productoId + ":" + sucursalId);
        if (s == null) {
            return List.of();
        }
        LocalDate hoy = LocalDate.now();
        return StockReconstructor.reconstruir(s.stockFinal, StockReconstructor.inicio(s.flujos, hoy), hoy, s.flujos);
    }

    /**
     * Resumen de disponibilidad por producto (total de sucursales) en el rango indicado. Los productos sin
     * movimientos ni stock no aparecen (resultado "no disponible").
     */
    public Map<Long, StockReconstructor.Resumen> resumenPorProducto(LocalDate desde, LocalDate hasta) {
        Map<Long, StockReconstructor.Resumen> out = new HashMap<>();
        LocalDate hoy = LocalDate.now();
        cargar(null, false).forEach((k, s) -> {
            List<DiaStock> dias = StockReconstructor.reconstruir(s.stockFinal, StockReconstructor.inicio(s.flujos, hoy),
                    hoy, s.flujos);
            out.put(Long.parseLong(k), StockReconstructor.resumir(dias, desde, hasta));
        });
        return out;
    }

    /** Reconstruye todas las combinaciones producto-sucursal y reemplaza la tabla de stock histórico. */
    public ResultadoReconstruccion reconstruirYGuardar() {
        LocalDate hoy = LocalDate.now();
        Map<String, Serie> series = cargar(null, true);
        int[] inconsistentes = {0};
        Set<Long> productosInconsistentes = new HashSet<>();
        db.inTransaction(() -> {
            repo.borrar(null);
            series.forEach((k, s) -> {
                String[] ps = k.split(":");
                long prod = Long.parseLong(ps[0]);
                List<DiaStock> dias = StockReconstructor.reconstruir(s.stockFinal,
                        StockReconstructor.inicio(s.flujos, hoy), hoy, s.flujos);
                if (dias.stream().anyMatch(DiaStock::negativo)) {
                    inconsistentes[0]++;
                    productosInconsistentes.add(prod);
                }
                repo.guardar(prod, Long.parseLong(ps[1]), dias);
            });
        });
        long dias = repo.contarDias();
        List<String> notas = new ArrayList<>();
        notas.add("Stock reconstruido hacia atrás desde el inventario actual con ventas, compras recibidas, ajustes y "
                + "traslados.");
        if (!repo.hayEntradas()) {
            notas.add(SIN_ENTRADAS);
        }
        if (inconsistentes[0] > 0) {
            notas.add(inconsistentes[0] + " combinación(es) producto-sucursal (" + productosInconsistentes.size()
                    + " producto(s)) presentan stock negativo en algún día: faltan compras o ajustes registrados "
                    + "antes de esas fechas. Importe las órdenes de compra recibidas para corregirlo.");
        }
        LOG.info("Stock histórico reconstruido: " + series.size() + " combinaciones, " + dias + " días.");
        return new ResultadoReconstruccion(series.size(), dias, inconsistentes[0], notas);
    }

    /** Stock guardado en la última reconstrucción (vacío si nunca se reconstruyó). */
    public List<DiaStock> guardado(long productoId, Long sucursalId) {
        return repo.guardado(productoId, sucursalId);
    }
}
