package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.recommendation.DecisionEstrategica;
import com.stockwise.recommendation.ProductDecision;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resultados por producto de cada corrida de análisis: decisión estratégica, niveles de inventario y, desde la v3,
 * pronósticos por horizonte, métricas del backtesting y la serie usada (para consultar análisis anteriores sin
 * recalcular).
 */
public class DecisionRepository {

    public static final String PENDIENTE = "PENDIENTE";
    public static final String APLICADA = "APLICADA";
    public static final String DESCARTADA = "DESCARTADA";
    /** El dueño decidió revisarla más adelante (en el próximo análisis se vuelve a proponer). */
    public static final String POSPUESTA = "POSPUESTA";

    /** Datos de análisis de un producto (nulos en resultados guardados antes de la v3). */
    public record DetalleAnalisis(double unidades30, double unidades90, double unidades365, String tendencia,
                                  double pendiente, String perfil, Double p7, Double p15, Double p30, Double p60,
                                  Double p90, Double sigmaMensual, String confianza, Double mae, Double rmse,
                                  Double wape, Double mape, Double sesgo, Double maeIngenuo, Integer puntosValidacion,
                                  String modelos, String seriePeriodos, String serieValores, String ajustes,
                                  String pronosticoMensual, String periodosFuturos, Double coberturaDias,
                                  Long diasSinVenta, String estadoInventario, Integer leadTime, String categoria,
                                  String advertencias, String rangos, String metricasHorizonte) {
    }

    /** Decisión guardada con la fotografía de los datos que la sustentaron. */
    public record DecisionGuardada(long id, String fechaGeneracion, LocalDate fechaCorte, long productoId,
                                   String codigo, String producto, DecisionEstrategica decision, String prioridad,
                                   String patron, String modelo, double demandaMensual, double stock,
                                   double enTransito, double stockSeguridad, double puntoReorden, double nivelObjetivo,
                                   int cantidadSugerida, double costoUnitario, double precioVenta,
                                   Double impactoEstimado, String motivo, String accion, String estado,
                                   Long analisisId, DetalleAnalisis detalle) {
    }

    private final Database db;

    public DecisionRepository(Database db) {
        this.db = db;
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static DecisionGuardada map(ResultSet rs) throws SQLException {
        long an = rs.getLong("analisis_id");
        Long analisis = rs.wasNull() ? null : an;
        DetalleAnalisis det = null;
        if (analisis != null) {
            long dsv = rs.getLong("dias_sin_venta");
            Long dias = rs.wasNull() ? null : dsv;
            int pv = rs.getInt("puntos_validacion");
            Integer puntos = rs.wasNull() ? null : pv;
            int ltv = rs.getInt("lead_time");
            Integer lt = rs.wasNull() ? null : ltv;
            det = new DetalleAnalisis(rs.getDouble("unidades30"), rs.getDouble("unidades90"),
                    rs.getDouble("unidades365"), rs.getString("tendencia"), rs.getDouble("pendiente"),
                    rs.getString("perfil"), dbl(rs, "p7"), dbl(rs, "p15"), dbl(rs, "p30"), dbl(rs, "p60"),
                    dbl(rs, "p90"), dbl(rs, "sigma_mensual"), rs.getString("confianza"), dbl(rs, "mae"),
                    dbl(rs, "rmse"), dbl(rs, "wape"), dbl(rs, "mape"), dbl(rs, "sesgo"), dbl(rs, "mae_ingenuo"),
                    puntos, rs.getString("modelos"), rs.getString("serie_periodos"), rs.getString("serie_valores"),
                    rs.getString("ajustes"), rs.getString("pronostico_mensual"), rs.getString("periodos_futuros"),
                    dbl(rs, "cobertura_dias"), dias, rs.getString("estado_inventario"), lt, rs.getString("categoria"),
                    rs.getString("advertencias"), rs.getString("rangos"), rs.getString("metricas_horizonte"));
        }
        return new DecisionGuardada(rs.getLong("id"), rs.getString("fecha_generacion"),
                LocalDate.parse(rs.getString("fecha_corte")), rs.getLong("producto_id"), rs.getString("codigo"),
                rs.getString("nombre"), DecisionEstrategica.valueOf(rs.getString("decision")),
                rs.getString("prioridad"), rs.getString("patron_demanda"), rs.getString("modelo"),
                rs.getDouble("demanda_mensual"), rs.getDouble("stock"), rs.getDouble("en_transito"),
                rs.getDouble("stock_seguridad"), rs.getDouble("punto_reorden"), rs.getDouble("nivel_objetivo"),
                rs.getInt("cantidad_sugerida"), rs.getDouble("costo_unitario"), rs.getDouble("precio_venta"),
                dbl(rs, "impacto_estimado"), rs.getString("motivo"), rs.getString("accion"), rs.getString("estado"),
                analisis, det);
    }

    private static final String SELECT = "SELECT d.*, p.codigo, p.nombre FROM decisiones d "
            + "JOIN productos p ON p.id = d.producto_id ";

    /** Guarda los resultados de una corrida de análisis (cada corrida conserva los suyos). */
    public void guardarCorrida(long analisisId, String fechaGeneracion, LocalDate fechaCorte,
                               List<ProductDecision> decisiones, Map<Long, DetalleAnalisis> detalles) {
        db.inTransaction(() -> {
            for (ProductDecision d : decisiones) {
                DetalleAnalisis x = detalles.get(d.productoId());
                db.insert("INSERT INTO decisiones(fecha_generacion, fecha_corte, producto_id, decision, prioridad, "
                                + "patron_demanda, modelo, demanda_mensual, stock, en_transito, stock_seguridad, "
                                + "punto_reorden, nivel_objetivo, cantidad_sugerida, costo_unitario, precio_venta, "
                                + "impacto_estimado, motivo, accion, analisis_id, unidades30, unidades90, unidades365, "
                                + "tendencia, pendiente, perfil, p7, p15, p30, p60, p90, sigma_mensual, confianza, mae, "
                                + "rmse, wape, mape, sesgo, mae_ingenuo, puntos_validacion, modelos, serie_periodos, "
                                + "serie_valores, ajustes, pronostico_mensual, periodos_futuros, cobertura_dias, "
                                + "dias_sin_venta, estado_inventario, lead_time, categoria, advertencias, rangos, metricas_horizonte) VALUES "
                                + "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                                + "?,?,?,?,?,?,?,?,?,?,?,?)",
                        fechaGeneracion, fechaCorte, d.productoId(), d.decision(), d.prioridad(),
                        d.patron() == null ? null : d.patron().name(), d.modelo(), d.metrics().demandaMensual(),
                        d.metrics().stock(), d.suministro().enTransito(), d.stockSeguridad(), d.puntoReorden(),
                        d.nivelObjetivo(), d.cantidadSugerida(), d.metrics().producto().costo(),
                        d.metrics().producto().precioVenta(), d.impactoEstimado(), d.motivo(), d.accion(), analisisId,
                        x == null ? null : x.unidades30(), x == null ? null : x.unidades90(),
                        x == null ? null : x.unidades365(), x == null ? null : x.tendencia(),
                        x == null ? null : x.pendiente(), x == null ? null : x.perfil(), x == null ? null : x.p7(),
                        x == null ? null : x.p15(), x == null ? null : x.p30(), x == null ? null : x.p60(),
                        x == null ? null : x.p90(), x == null ? null : x.sigmaMensual(),
                        x == null ? null : x.confianza(), x == null ? null : x.mae(), x == null ? null : x.rmse(),
                        x == null ? null : x.wape(), x == null ? null : x.mape(), x == null ? null : x.sesgo(),
                        x == null ? null : x.maeIngenuo(), x == null ? null : x.puntosValidacion(),
                        x == null ? null : x.modelos(), x == null ? null : x.seriePeriodos(),
                        x == null ? null : x.serieValores(), x == null ? null : x.ajustes(),
                        x == null ? null : x.pronosticoMensual(), x == null ? null : x.periodosFuturos(),
                        x == null ? null : x.coberturaDias(), x == null ? null : x.diasSinVenta(),
                        x == null ? null : x.estadoInventario(), x == null ? null : x.leadTime(),
                        x == null ? null : x.categoria(), x == null ? null : x.advertencias(),
                        x == null ? null : x.rangos(), x == null ? null : x.metricasHorizonte());
            }
        });
    }

    /** Resultados de una corrida. */
    public List<DecisionGuardada> porAnalisis(long analisisId) {
        return db.query(SELECT + "WHERE d.analisis_id = ? ORDER BY d.id", DecisionRepository::map, analisisId);
    }

    /** Decisiones con la fecha de corte indicada (de la corrida más reciente con ese corte). */
    public List<DecisionGuardada> porFechaCorte(LocalDate fechaCorte) {
        Optional<Long> ultima = db.queryOne("SELECT MAX(COALESCE(analisis_id, 0)) FROM decisiones WHERE fecha_corte = ?",
                rs -> rs.getLong(1), fechaCorte);
        long id = ultima.orElse(0L);
        if (id > 0) {
            return porAnalisis(id);
        }
        return db.query(SELECT + "WHERE d.fecha_corte = ? AND d.analisis_id IS NULL ORDER BY d.id",
                DecisionRepository::map, fechaCorte);
    }

    /** Última fecha de corte con decisiones guardadas hasta {@code limite} (inclusive). */
    public Optional<LocalDate> ultimaFechaCorteHasta(LocalDate limite) {
        return db.queryOne("SELECT MAX(fecha_corte) FROM decisiones WHERE fecha_corte <= ?",
                rs -> rs.getString(1), limite).map(LocalDate::parse);
    }

    public Optional<DecisionGuardada> findById(long id) {
        return db.queryOne(SELECT + "WHERE d.id = ?", DecisionRepository::map, id);
    }

    public void updateEstado(long id, String estado) {
        db.update("UPDATE decisiones SET estado = ? WHERE id = ?", estado, id);
    }

    public long count() {
        return db.queryLong("SELECT COUNT(*) FROM decisiones");
    }
}
