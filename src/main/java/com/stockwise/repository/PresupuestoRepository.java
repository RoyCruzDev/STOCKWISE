package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Presupuesto;

import java.util.List;
import java.util.Optional;

public class PresupuestoRepository {
    private final Database db;

    public PresupuestoRepository(Database db) {
        this.db = db;
    }

    public List<Presupuesto> findAll() {
        return db.query("SELECT pr.id, pr.periodo, pr.sucursal_id, s.codigo, pr.monto, pr.descripcion "
                        + "FROM presupuestos pr LEFT JOIN sucursales s ON s.id = pr.sucursal_id "
                        + "ORDER BY pr.periodo DESC, s.codigo",
                rs -> new Presupuesto(rs.getLong(1), rs.getString(2), Database.getNullableLong(rs, "sucursal_id"),
                        rs.getString(4), rs.getDouble(5), rs.getString(6)));
    }

    /** Suma de presupuestos (general + sucursales) de un periodo YYYY-MM; vacío si no hay ninguno. */
    public Optional<Double> totalPeriodo(String periodo) {
        long n = db.queryLong("SELECT COUNT(*) FROM presupuestos WHERE periodo = ?", periodo);
        if (n == 0) {
            return Optional.empty();
        }
        return Optional.of(db.queryDouble("SELECT SUM(monto) FROM presupuestos WHERE periodo = ?", periodo));
    }

    public boolean exists(String periodo, Long sucursalId) {
        return db.queryLong("SELECT COUNT(*) FROM presupuestos WHERE periodo = ? AND IFNULL(sucursal_id,0) = ?",
                periodo, sucursalId == null ? 0L : sucursalId) > 0;
    }

    public void upsert(String periodo, Long sucursalId, double monto, String descripcion) {
        if (exists(periodo, sucursalId)) {
            db.update("UPDATE presupuestos SET monto = ?, descripcion = ? WHERE periodo = ? "
                    + "AND IFNULL(sucursal_id,0) = ?", monto, descripcion, periodo, sucursalId == null ? 0L : sucursalId);
        } else {
            db.insert("INSERT INTO presupuestos(periodo, sucursal_id, monto, descripcion) VALUES (?,?,?,?)",
                    periodo, sucursalId, monto, descripcion);
        }
    }

    public void delete(long id) {
        db.update("DELETE FROM presupuestos WHERE id = ?", id);
    }
}
