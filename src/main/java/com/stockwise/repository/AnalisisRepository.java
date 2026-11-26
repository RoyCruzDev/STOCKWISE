package com.stockwise.repository;

import com.stockwise.database.Database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Corridas de análisis guardadas (cabecera). Los resultados por producto están en la tabla decisiones. */
public class AnalisisRepository {

    public static final String COMPLETADO = "COMPLETADO";

    /** Cabecera de una corrida de análisis. */
    public record Analisis(long id, String fecha, LocalDate fechaCorte, String estado, Long duracionMs, int productos,
                           int conPronostico, LocalDate ventasDesde, LocalDate ventasHasta, long registrosVentas,
                           Double presupuesto, Double compraSugerida, Double compraAprobada, Double wape, Double sesgo,
                           Double fva, String notas) {
    }

    private final Database db;

    public AnalisisRepository(Database db) {
        this.db = db;
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDate fecha(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s);
    }

    private static Analisis map(ResultSet rs) throws SQLException {
        long dur = rs.getLong("duracion_ms");
        Long duracion = rs.wasNull() ? null : dur;
        return new Analisis(rs.getLong("id"), rs.getString("fecha"), LocalDate.parse(rs.getString("fecha_corte")),
                rs.getString("estado"), duracion, rs.getInt("productos"), rs.getInt("con_pronostico"),
                fecha(rs.getString("ventas_desde")), fecha(rs.getString("ventas_hasta")), rs.getLong("registros_ventas"),
                dbl(rs, "presupuesto"), dbl(rs, "compra_sugerida"), dbl(rs, "compra_aprobada"), dbl(rs, "wape"),
                dbl(rs, "sesgo"), dbl(rs, "fva"), rs.getString("notas"));
    }

    public long insertar(Analisis a) {
        return db.insert("INSERT INTO analisis(fecha, fecha_corte, estado, duracion_ms, productos, con_pronostico, "
                        + "ventas_desde, ventas_hasta, registros_ventas, presupuesto, compra_sugerida, compra_aprobada, "
                        + "wape, sesgo, fva, notas) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                a.fecha(), a.fechaCorte(), a.estado(), a.duracionMs(), a.productos(), a.conPronostico(), a.ventasDesde(),
                a.ventasHasta(), a.registrosVentas(), a.presupuesto(), a.compraSugerida(), a.compraAprobada(), a.wape(),
                a.sesgo(), a.fva(), a.notas());
    }

    public List<Analisis> listar() {
        return db.query("SELECT * FROM analisis ORDER BY id DESC", AnalisisRepository::map);
    }

    public Optional<Analisis> findById(long id) {
        return db.queryOne("SELECT * FROM analisis WHERE id = ?", AnalisisRepository::map, id);
    }

    public Optional<Analisis> ultimo() {
        return db.queryOne("SELECT * FROM analisis ORDER BY id DESC LIMIT 1", AnalisisRepository::map);
    }

    /** Último análisis con fecha de corte igual o anterior al límite. */
    public Optional<Analisis> ultimoConCorteHasta(LocalDate limite) {
        return db.queryOne("SELECT * FROM analisis WHERE fecha_corte <= ? ORDER BY fecha_corte DESC, id DESC LIMIT 1",
                AnalisisRepository::map, limite);
    }

    /** Elimina la corrida y sus resultados por producto. Nunca toca ventas, compras ni inventario. */
    public void eliminar(long id) {
        db.inTransaction(() -> {
            db.update("DELETE FROM decisiones WHERE analisis_id = ?", id);
            db.update("DELETE FROM analisis WHERE id = ?", id);
        });
    }

    public long count() {
        return db.queryLong("SELECT COUNT(*) FROM analisis");
    }
}
