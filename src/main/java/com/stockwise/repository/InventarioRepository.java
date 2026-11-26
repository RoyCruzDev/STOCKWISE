package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.InventarioItem;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InventarioRepository {
    private static final String SELECT = "SELECT i.id, i.producto_id, p.codigo AS pcod, p.nombre AS pnom, "
            + "i.sucursal_id, s.codigo AS scod, s.nombre AS snom, i.stock, p.costo, p.precio_venta, "
            + "p.stock_minimo, p.stock_maximo, i.fecha_actualizacion FROM inventario i "
            + "JOIN productos p ON p.id = i.producto_id JOIN sucursales s ON s.id = i.sucursal_id ";

    private final Database db;

    public InventarioRepository(Database db) {
        this.db = db;
    }

    private static InventarioItem map(ResultSet rs) throws SQLException {
        return new InventarioItem(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getString(6), rs.getString(7), rs.getInt(8), rs.getDouble(9), rs.getDouble(10), rs.getInt(11),
                rs.getInt(12), rs.getString(13));
    }

    public List<InventarioItem> findAll() {
        return db.query(SELECT + "ORDER BY p.codigo, s.codigo", InventarioRepository::map);
    }

    public List<InventarioItem> findBySucursal(long sucursalId) {
        return db.query(SELECT + "WHERE i.sucursal_id = ? ORDER BY p.codigo", InventarioRepository::map, sucursalId);
    }

    public List<InventarioItem> findByProducto(long productoId) {
        return db.query(SELECT + "WHERE i.producto_id = ? ORDER BY s.codigo", InventarioRepository::map, productoId);
    }

    public int getStock(long productoId, long sucursalId) {
        return (int) db.queryLong("SELECT stock FROM inventario WHERE producto_id = ? AND sucursal_id = ?",
                productoId, sucursalId);
    }

    public boolean exists(long productoId, long sucursalId) {
        return db.queryLong("SELECT COUNT(*) FROM inventario WHERE producto_id = ? AND sucursal_id = ?",
                productoId, sucursalId) > 0;
    }

    /** Stock total por producto (todas las sucursales). */
    public Map<Long, Integer> stockTotalPorProducto() {
        Map<Long, Integer> map = new HashMap<>();
        db.query("SELECT producto_id, SUM(stock) FROM inventario GROUP BY producto_id",
                rs -> map.put(rs.getLong(1), rs.getInt(2)));
        return map;
    }

    public void upsertStock(long productoId, long sucursalId, int stock) {
        db.update("INSERT INTO inventario(producto_id, sucursal_id, stock, fecha_actualizacion) VALUES (?,?,?,?) "
                        + "ON CONFLICT(producto_id, sucursal_id) DO UPDATE SET stock = excluded.stock, "
                        + "fecha_actualizacion = excluded.fecha_actualizacion",
                productoId, sucursalId, stock, LocalDateTime.now().withNano(0).toString().replace('T', ' '));
    }

    public double valorTotalCosto() {
        return db.queryDouble("SELECT COALESCE(SUM(i.stock * p.costo),0) FROM inventario i "
                + "JOIN productos p ON p.id = i.producto_id");
    }

    public long unidadesTotales() {
        return db.queryLong("SELECT COALESCE(SUM(stock),0) FROM inventario");
    }

    /**
     * Verdadero si alguna vez se registró inventario en el proyecto (importado o ingresado). Sin inventario el stock
     * es desconocido, no cero: no se pueden calcular faltantes, excesos ni cantidades a comprar.
     */
    public boolean hayRegistros() {
        return db.queryLong("SELECT COUNT(*) FROM inventario") > 0;
    }
}
