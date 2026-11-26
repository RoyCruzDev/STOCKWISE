package com.stockwise.repository;

import com.stockwise.database.Database;

import java.time.LocalDateTime;
import java.util.List;

public class PrediccionRepository {
    private final Database db;

    public record PrediccionGuardada(String periodo, String metodo, double valor, Double mae, Double mape,
                                     String confianza, String fechaGeneracion) {
    }

    public PrediccionRepository(Database db) {
        this.db = db;
    }

    /** Reemplaza las predicciones almacenadas de un producto. */
    public void replace(long productoId, String metodo, List<String> periodos, double[] valores, Double mae,
                        Double mape, String confianza) {
        db.inTransaction(() -> {
            db.update("DELETE FROM predicciones WHERE producto_id = ?", productoId);
            String now = LocalDateTime.now().withNano(0).toString().replace('T', ' ');
            for (int i = 0; i < periodos.size(); i++) {
                db.insert("INSERT INTO predicciones(producto_id, fecha_generacion, metodo, periodo, valor, mae, mape, "
                                + "confianza) VALUES (?,?,?,?,?,?,?,?)",
                        productoId, now, metodo, periodos.get(i), valores[i], mae, mape, confianza);
            }
        });
    }

    public List<PrediccionGuardada> findByProducto(long productoId) {
        return db.query("SELECT periodo, metodo, valor, mae, mape, confianza, fecha_generacion FROM predicciones "
                        + "WHERE producto_id = ? ORDER BY periodo",
                rs -> new PrediccionGuardada(rs.getString(1), rs.getString(2), rs.getDouble(3),
                        rs.getObject(4) == null ? null : rs.getDouble(4),
                        rs.getObject(5) == null ? null : rs.getDouble(5), rs.getString(6), rs.getString(7)),
                productoId);
    }
}
