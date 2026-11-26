package com.stockwise.repository;

import com.stockwise.analytics.StockReconstructor.DiaStock;
import com.stockwise.database.Database;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Flujos diarios de inventario y almacenamiento del stock histórico reconstruido. */
public class StockHistoricoRepository {

    /** Flujo agregado de un producto en una sucursal y día (magnitudes positivas). */
    public record Flujo(long productoId, long sucursalId, LocalDate fecha, double entradas, double salidas) {
    }

    private final Database db;

    public StockHistoricoRepository(Database db) {
        this.db = db;
    }

    private static String filtro(Long productoId, String alias) {
        return productoId == null ? "" : " AND " + alias + "producto_id = " + productoId;
    }

    /** Ventas por día (salidas). */
    public List<Flujo> ventasPorDia(Long productoId) {
        return db.query("SELECT producto_id, sucursal_id, fecha, SUM(cantidad) FROM ventas WHERE 1=1"
                        + filtro(productoId, "") + " GROUP BY producto_id, sucursal_id, fecha",
                rs -> new Flujo(rs.getLong(1), rs.getLong(2), LocalDate.parse(rs.getString(3).substring(0, 10)), 0,
                        rs.getDouble(4)));
    }

    /** Compras recibidas por día de recepción (entradas). */
    public List<Flujo> comprasRecibidasPorDia(Long productoId) {
        return db.query("SELECT producto_id, sucursal_id, substr(COALESCE(fecha_recepcion, fecha),1,10) AS f, "
                        + "SUM(cantidad) FROM compras WHERE estado = 'RECIBIDA'" + filtro(productoId, "")
                        + " GROUP BY producto_id, sucursal_id, f",
                rs -> new Flujo(rs.getLong(1), rs.getLong(2), LocalDate.parse(rs.getString(3)), rs.getDouble(4), 0));
    }

    /**
     * Movimientos que no están representados en ventas ni compras: entradas, salidas, ajustes y traslados.
     * Se excluyen VENTA y COMPRA (ya contados por sus tablas) e IMPORTACION (carga inicial = medición).
     */
    public List<Flujo> otrosMovimientosPorDia(Long productoId) {
        return db.query("SELECT producto_id, sucursal_id, substr(fecha,1,10) AS f, "
                        + "SUM(CASE WHEN cantidad > 0 THEN cantidad ELSE 0 END), "
                        + "SUM(CASE WHEN cantidad < 0 THEN -cantidad ELSE 0 END) FROM movimientos_inventario "
                        + "WHERE tipo NOT IN ('VENTA','COMPRA','IMPORTACION')" + filtro(productoId, "")
                        + " GROUP BY producto_id, sucursal_id, f",
                rs -> new Flujo(rs.getLong(1), rs.getLong(2), LocalDate.parse(rs.getString(3)), rs.getDouble(4),
                        rs.getDouble(5)));
    }

    /** Stock actual por producto y sucursal: {productoId, sucursalId, stock}. */
    public List<long[]> stockActual(Long productoId) {
        return db.query("SELECT producto_id, sucursal_id, stock FROM inventario WHERE 1=1" + filtro(productoId, ""),
                rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
    }

    /** Guarda (insertando o reemplazando) los días reconstruidos de un producto en una sucursal. */
    public void guardar(long prodId, long sucursalId, List<DiaStock> dias) {
        db.withConnection(c -> {
            try (var ps = c.prepareStatement("INSERT OR REPLACE INTO stock_historico(producto_id, sucursal_id, fecha, "
                    + "stock, entradas, salidas) VALUES (?,?,?,?,?,?)")) {
                for (DiaStock d : dias) {
                    ps.setLong(1, prodId);
                    ps.setLong(2, sucursalId);
                    ps.setString(3, d.fecha().toString());
                    ps.setDouble(4, d.stock());
                    ps.setDouble(5, d.entradas());
                    ps.setDouble(6, d.salidas());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void borrar(Long productoId) {
        db.update("DELETE FROM stock_historico WHERE 1=1" + filtro(productoId, ""));
    }

    /** Verdadero si existe algún registro de reposición (compra recibida, entrada o traslado recibido). */
    public boolean hayEntradas() {
        return db.queryLong("SELECT (SELECT COUNT(*) FROM compras WHERE estado = 'RECIBIDA') + "
                + "(SELECT COUNT(*) FROM movimientos_inventario WHERE tipo IN ('ENTRADA','TRASLADO_ENTRADA','COMPRA'))") > 0;
    }

    public long contarDias() {
        return db.queryLong("SELECT COUNT(*) FROM stock_historico");
    }

    /** Stock guardado de un producto (total de sucursales o de una sucursal). */
    public List<DiaStock> guardado(long productoId, Long sucursalId) {
        List<Object> p = new ArrayList<>();
        p.add(productoId);
        String suc = "";
        if (sucursalId != null) {
            suc = " AND sucursal_id = ?";
            p.add(sucursalId);
        }
        return db.query("SELECT fecha, SUM(stock), SUM(entradas), SUM(salidas) FROM stock_historico WHERE "
                        + "producto_id = ?" + suc + " GROUP BY fecha ORDER BY fecha",
                rs -> new DiaStock(LocalDate.parse(rs.getString(1)), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4)), p.toArray());
    }
}
