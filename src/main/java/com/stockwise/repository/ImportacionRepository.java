package com.stockwise.repository;

import com.stockwise.database.Database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Historial de importaciones y vínculo importación ↔ registro. Un registro (venta, compra o devolución) puede estar
 * vinculado a varias importaciones si varios archivos lo contenían; el vínculo {@code nuevo = 1} indica la
 * importación "dueña" (la que lo creó o la que lo heredó al eliminarse la dueña anterior).
 */
public class ImportacionRepository {

    public static final String COMPLETADA = "COMPLETADA";
    public static final String ELIMINADA = "ELIMINADA";
    /** Importación por bloques cancelada o interrumpida: sus bloques guardados son válidos y siguen activos. */
    public static final String PARCIAL = "PARCIAL";
    /** Ninguna fila se pudo guardar (todas con error): queda en el historial con el motivo, sin registros. */
    public static final String FALLIDA = "FALLIDA";
    /** Estados cuyos registros cuentan como pertenecientes a una importación vigente. */
    static final String ACTIVAS = "('COMPLETADA','PARCIAL')";

    /** Importación registrada. */
    public record Importacion(long id, String fecha, String archivo, String hoja, String tipo, LocalDate periodoDesde,
                              LocalDate periodoHasta, int filas, int insertados, int actualizados, int duplicados,
                              int errores, int omitidos, String estructura, String estado, String detalle) {
    }

    private final Database db;

    public ImportacionRepository(Database db) {
        this.db = db;
    }

    private static LocalDate fecha(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s);
    }

    private static Importacion map(ResultSet rs) throws SQLException {
        return new Importacion(rs.getLong("id"), rs.getString("fecha"), rs.getString("archivo"), rs.getString("hoja"),
                rs.getString("tipo"), fecha(rs.getString("periodo_desde")), fecha(rs.getString("periodo_hasta")),
                rs.getInt("filas"), rs.getInt("insertados"), rs.getInt("actualizados"), rs.getInt("duplicados"),
                rs.getInt("errores"), rs.getInt("omitidos"), rs.getString("estructura"), rs.getString("estado"),
                rs.getString("detalle"));
    }

    public long insertar(String fecha, String archivo, String hoja, String tipo, LocalDate desde, LocalDate hasta,
                         int filas, String estructura) {
        return db.insert("INSERT INTO importaciones(fecha, archivo, hoja, tipo, periodo_desde, periodo_hasta, filas, "
                + "estructura) VALUES (?,?,?,?,?,?,?,?)", fecha, archivo, hoja, tipo, desde, hasta, filas, estructura);
    }

    public void actualizarConteos(long id, int insertados, int actualizados, int duplicados, int errores,
                                  int omitidos) {
        db.update("UPDATE importaciones SET insertados=?, actualizados=?, duplicados=?, errores=?, omitidos=? "
                + "WHERE id=?", insertados, actualizados, duplicados, errores, omitidos, id);
    }

    /** Motivos de los errores (resumen agrupado) para consultarlos después desde el historial. */
    public void guardarDetalle(long id, String detalle) {
        db.update("UPDATE importaciones SET detalle = ? WHERE id = ?", detalle, id);
    }

    public void vincular(long importacionId, String tabla, long registroId, boolean nuevo) {
        db.update("INSERT INTO importacion_registros(importacion_id, tabla, registro_id, nuevo) VALUES (?,?,?,?) "
                        + "ON CONFLICT(importacion_id, tabla, registro_id) DO UPDATE SET nuevo = MAX(nuevo, excluded.nuevo)",
                importacionId, tabla, registroId, nuevo ? 1 : 0);
    }

    public List<Importacion> listar() {
        return db.query("SELECT * FROM importaciones ORDER BY id DESC", ImportacionRepository::map);
    }

    public Optional<Importacion> findById(long id) {
        return db.queryOne("SELECT * FROM importaciones WHERE id = ?", ImportacionRepository::map, id);
    }

    /** Importaciones activas del mismo tipo cuyo periodo se superpone con [desde, hasta]. */
    public List<Importacion> solapadas(String tipo, LocalDate desde, LocalDate hasta) {
        return db.query("SELECT * FROM importaciones WHERE estado IN " + ACTIVAS + " AND tipo = ? AND periodo_desde IS NOT "
                        + "NULL AND periodo_desde <= ? AND periodo_hasta >= ? ORDER BY id",
                ImportacionRepository::map, tipo, hasta, desde);
    }

    /** Estructura (encabezados normalizados) de la última importación activa del tipo. */
    public Optional<String> ultimaEstructura(String tipo) {
        return db.queryOne("SELECT estructura FROM importaciones WHERE tipo = ? AND estado IN " + ACTIVAS + " "
                + "AND estructura IS NOT NULL ORDER BY id DESC LIMIT 1", rs -> rs.getString(1), tipo);
    }

    /** Registros de la tabla de los que esta importación es dueña y que ninguna otra importación activa contiene. */
    public List<Long> exclusivos(long importacionId, String tabla) {
        return db.query("SELECT r.registro_id FROM importacion_registros r WHERE r.importacion_id = ? AND r.tabla = ? "
                + "AND r.nuevo = 1 AND NOT EXISTS (SELECT 1 FROM importacion_registros o JOIN importaciones i "
                + "ON i.id = o.importacion_id WHERE o.tabla = r.tabla AND o.registro_id = r.registro_id "
                + "AND o.importacion_id <> r.importacion_id AND i.estado IN " + ACTIVAS + ")", rs -> rs.getLong(1),
                importacionId, tabla);
    }

    /** Registros de la tabla vinculados a esta importación y también a otras importaciones activas. */
    public List<Long> compartidos(long importacionId, String tabla) {
        return db.query("SELECT DISTINCT r.registro_id FROM importacion_registros r JOIN importacion_registros o "
                + "ON o.tabla = r.tabla AND o.registro_id = r.registro_id AND o.importacion_id <> r.importacion_id "
                + "JOIN importaciones i ON i.id = o.importacion_id AND i.estado IN " + ACTIVAS + " "
                + "WHERE r.importacion_id = ? AND r.tabla = ?", rs -> rs.getLong(1), importacionId, tabla);
    }

    /** Vinculados a esta importación pero creados fuera de ella (registro manual o anterior al historial). */
    public long ajenos(long importacionId, String tabla) {
        return db.queryLong("SELECT COUNT(*) FROM importacion_registros r WHERE r.importacion_id = ? AND r.tabla = ? "
                + "AND r.nuevo = 0 AND NOT EXISTS (SELECT 1 FROM importacion_registros o JOIN importaciones i "
                + "ON i.id = o.importacion_id WHERE o.tabla = r.tabla AND o.registro_id = r.registro_id "
                + "AND o.importacion_id <> r.importacion_id AND i.estado IN " + ACTIVAS + ")", importacionId, tabla);
    }

    /**
     * Transfiere la propiedad de los registros compartidos a otra importación activa que los contiene, para que al
     * eliminarse esta los datos sigan perteneciendo a un archivo que aún existe.
     */
    public void transferirPropiedad(long importacionId) {
        db.update("UPDATE importacion_registros SET nuevo = 1 WHERE rowid IN (SELECT MIN(o.rowid) FROM "
                + "importacion_registros r JOIN importacion_registros o ON o.tabla = r.tabla AND o.registro_id = "
                + "r.registro_id AND o.importacion_id <> r.importacion_id JOIN importaciones i ON i.id = "
                + "o.importacion_id AND i.estado IN " + ACTIVAS + " WHERE r.importacion_id = ? AND r.nuevo = 1 "
                + "GROUP BY o.tabla, o.registro_id)", importacionId);
    }

    /** Quita del historial una importación que no llegó a guardar ningún registro. */
    public void borrarSinRegistros(long importacionId) {
        if (db.queryLong("SELECT COUNT(*) FROM importacion_registros WHERE importacion_id = ?", importacionId) == 0) {
            db.update("DELETE FROM importaciones WHERE id = ?", importacionId);
        }
    }

    public void marcarEstado(long importacionId, String estado) {
        db.update("UPDATE importaciones SET estado = ? WHERE id = ?", estado, importacionId);
    }

    public void actualizarPeriodo(long importacionId, LocalDate desde, LocalDate hasta) {
        db.update("UPDATE importaciones SET periodo_desde = ?, periodo_hasta = ? WHERE id = ?", desde, hasta,
                importacionId);
    }

    public void marcarEliminada(long importacionId) {
        db.update("DELETE FROM importacion_registros WHERE importacion_id = ?", importacionId);
        db.update("UPDATE importaciones SET estado = 'ELIMINADA' WHERE id = ?", importacionId);
    }

    /** Registros de una tabla que no pertenecen a ninguna importación (datos anteriores al historial o manuales). */
    public long sinImportacion(String tabla) {
        return db.queryLong("SELECT COUNT(*) FROM " + tabla + " t WHERE NOT EXISTS (SELECT 1 FROM "
                + "importacion_registros r JOIN importaciones i ON i.id = r.importacion_id AND i.estado IN " + ACTIVAS + " "
                + "WHERE r.tabla = ? AND r.registro_id = t.id)", tabla);
    }

    public long contarActivas() {
        return db.queryLong("SELECT COUNT(*) FROM importaciones WHERE estado IN " + ACTIVAS + "");
    }
}
