package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Prioridad;
import com.stockwise.model.Recomendacion;
import com.stockwise.model.TipoRecomendacion;

import java.util.List;

public class RecomendacionRepository {
    public static final String PENDIENTE = "PENDIENTE";
    public static final String APLICADA = "APLICADA";
    public static final String DESCARTADA = "DESCARTADA";

    private final Database db;

    public RecomendacionRepository(Database db) {
        this.db = db;
    }

    /** Sustituye las recomendaciones pendientes por un nuevo lote (las aplicadas/descartadas se conservan). */
    public void replacePendientes(List<Recomendacion> nuevas) {
        db.inTransaction(() -> {
            db.update("DELETE FROM recomendaciones WHERE estado = ?", PENDIENTE);
            for (Recomendacion r : nuevas) {
                db.insert("INSERT INTO recomendaciones(fecha_generacion, tipo, prioridad, producto_id, sucursal_id, "
                                + "sucursal_destino_id, problema, accion, justificacion, beneficio, estado) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        r.fechaGeneracion(), r.tipo(), r.prioridad(), r.productoId(), r.sucursalId(),
                        r.sucursalDestinoId(), r.problema(), r.accion(), r.justificacion(), r.beneficio(),
                        r.estado() == null ? PENDIENTE : r.estado());
            }
        });
    }

    public List<Recomendacion> findAll() {
        return db.query("SELECT r.*, p.codigo || ' - ' || p.nombre AS prod, s.codigo AS suc, d.codigo AS dest "
                        + "FROM recomendaciones r LEFT JOIN productos p ON p.id = r.producto_id "
                        + "LEFT JOIN sucursales s ON s.id = r.sucursal_id "
                        + "LEFT JOIN sucursales d ON d.id = r.sucursal_destino_id "
                        + "ORDER BY CASE r.estado WHEN 'PENDIENTE' THEN 0 ELSE 1 END, "
                        + "CASE r.prioridad WHEN 'CRITICA' THEN 0 WHEN 'ALTA' THEN 1 WHEN 'MEDIA' THEN 2 ELSE 3 END, "
                        + "COALESCE(r.beneficio,0) DESC",
                rs -> new Recomendacion(rs.getLong("id"), rs.getString("fecha_generacion"),
                        TipoRecomendacion.valueOf(rs.getString("tipo")), Prioridad.valueOf(rs.getString("prioridad")),
                        Database.getNullableLong(rs, "producto_id"), rs.getString("prod"),
                        Database.getNullableLong(rs, "sucursal_id"), rs.getString("suc"),
                        Database.getNullableLong(rs, "sucursal_destino_id"), rs.getString("dest"),
                        rs.getString("problema"), rs.getString("accion"), rs.getString("justificacion"),
                        rs.getObject("beneficio") == null ? null : rs.getDouble("beneficio"), rs.getString("estado")));
    }

    public void updateEstado(long id, String estado) {
        db.update("UPDATE recomendaciones SET estado = ? WHERE id = ?", estado, id);
    }
}
