package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Movimiento;
import com.stockwise.model.TipoMovimiento;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MovimientoRepository {
    private final Database db;

    public MovimientoRepository(Database db) {
        this.db = db;
    }

    public long insert(long productoId, long sucursalId, TipoMovimiento tipo, int cantidad, int stockResultante,
                       String nota) {
        return db.insert("INSERT INTO movimientos_inventario(fecha, producto_id, sucursal_id, tipo, cantidad, "
                        + "stock_resultante, nota) VALUES (?,?,?,?,?,?,?)",
                LocalDateTime.now().withNano(0).toString().replace('T', ' '), productoId, sucursalId, tipo,
                cantidad, stockResultante, nota);
    }

    public List<Movimiento> find(Long productoId, Long sucursalId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT m.id, m.fecha, m.producto_id, p.codigo, p.nombre, "
                + "m.sucursal_id, s.codigo, m.tipo, m.cantidad, m.stock_resultante, m.nota "
                + "FROM movimientos_inventario m JOIN productos p ON p.id = m.producto_id "
                + "JOIN sucursales s ON s.id = m.sucursal_id WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (productoId != null) {
            sql.append("AND m.producto_id = ? ");
            params.add(productoId);
        }
        if (sucursalId != null) {
            sql.append("AND m.sucursal_id = ? ");
            params.add(sucursalId);
        }
        sql.append("ORDER BY m.id DESC LIMIT ").append(Math.max(1, limit));
        return db.query(sql.toString(), rs -> new Movimiento(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                        TipoMovimiento.valueOf(rs.getString(8)), rs.getInt(9), rs.getInt(10), rs.getString(11)),
                params.toArray());
    }
}
