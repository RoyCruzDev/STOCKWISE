package com.stockwise.repository;

import com.stockwise.database.Database;

import java.time.LocalDate;
import java.util.Optional;

/** Devoluciones / notas de crédito: se restan de la demanda en las series de predicción. */
public class DevolucionRepository {

    private final Database db;

    public DevolucionRepository(Database db) {
        this.db = db;
    }

    public static String huella(LocalDate fecha, long productoId, long sucursalId, double cantidad, double precio,
                                String referencia) {
        return "D|" + VentaRepository.huella(fecha, productoId, sucursalId, cantidad, precio, referencia);
    }

    public long insert(LocalDate fecha, long productoId, long sucursalId, double cantidad, double total,
                       String referencia, String huella) {
        return db.insert("INSERT INTO devoluciones(fecha, producto_id, sucursal_id, cantidad, total, referencia, huella) "
                + "VALUES (?,?,?,?,?,?,?)", fecha, productoId, sucursalId, cantidad, total, referencia, huella);
    }

    public Optional<Long> idPorHuella(String huella) {
        return db.queryOne("SELECT id FROM devoluciones WHERE huella = ?", rs -> rs.getLong(1), huella);
    }

    public long count() {
        return db.queryLong("SELECT COUNT(*) FROM devoluciones");
    }

    public double unidades() {
        return db.queryDouble("SELECT COALESCE(SUM(cantidad),0) FROM devoluciones");
    }
}
