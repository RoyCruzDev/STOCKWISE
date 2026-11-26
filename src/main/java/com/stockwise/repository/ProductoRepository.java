package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Producto;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class ProductoRepository {
    private static final String SELECT = "SELECT p.*, c.nombre AS categoria, pr.nombre AS proveedor FROM productos p "
            + "LEFT JOIN categorias c ON c.id = p.categoria_id LEFT JOIN proveedores pr ON pr.id = p.proveedor_id ";

    private final Database db;

    public ProductoRepository(Database db) {
        this.db = db;
    }

    private static Producto map(ResultSet rs) throws SQLException {
        long lt = rs.getLong("lead_time_dias");
        Integer leadTime = rs.wasNull() ? null : (int) lt;
        return new Producto(rs.getLong("id"), rs.getString("codigo"), rs.getString("nombre"),
                Database.getNullableLong(rs, "categoria_id"), rs.getString("categoria"),
                Database.getNullableLong(rs, "proveedor_id"), rs.getString("proveedor"),
                rs.getDouble("precio_venta"), rs.getDouble("costo"), rs.getInt("stock_minimo"),
                rs.getInt("stock_maximo"), leadTime, rs.getInt("activo") == 1);
    }

    public List<Producto> findAll() {
        return db.query(SELECT + "ORDER BY p.codigo", ProductoRepository::map);
    }

    public List<Producto> findActivos() {
        return db.query(SELECT + "WHERE p.activo = 1 ORDER BY p.codigo", ProductoRepository::map);
    }

    public Optional<Producto> findById(long id) {
        return db.queryOne(SELECT + "WHERE p.id = ?", ProductoRepository::map, id);
    }

    public Optional<Producto> findByCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return Optional.empty();
        }
        return db.queryOne(SELECT + "WHERE p.codigo = ? COLLATE NOCASE", ProductoRepository::map, codigo.trim());
    }

    /**
     * Código numérico que perdió (o ganó) ceros a la izquierda al pasar por Excel: "000123" y "123" son el mismo
     * producto si solo uno del catálogo coincide.
     */
    public Optional<Producto> findByCodigoSinCeros(String codigo) {
        if (codigo == null || !codigo.trim().matches("[0-9]+")) {
            return Optional.empty();
        }
        String sin = codigo.trim().replaceFirst("^0+(?=.)", "");
        List<Producto> l = db.query(SELECT + "WHERE LTRIM(p.codigo, '0') = ? LIMIT 2", ProductoRepository::map, sin);
        return l.size() == 1 ? Optional.of(l.get(0)) : Optional.empty();
    }

    /** Producto con ese nombre exacto (sin distinguir mayúsculas); vacío si no hay ninguno o hay más de uno. */
    public Optional<Producto> findByNombreUnico(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return Optional.empty();
        }
        List<Producto> l = db.query(SELECT + "WHERE TRIM(p.nombre) = ? COLLATE NOCASE LIMIT 2", ProductoRepository::map,
                nombre.trim());
        return l.size() == 1 ? Optional.of(l.get(0)) : Optional.empty();
    }

    public long insert(Producto p) {
        return db.insert("INSERT INTO productos(codigo, nombre, categoria_id, proveedor_id, precio_venta, costo, "
                        + "stock_minimo, stock_maximo, lead_time_dias, activo) VALUES (?,?,?,?,?,?,?,?,?,?)",
                p.codigo().trim(), p.nombre().trim(), p.categoriaId(), p.proveedorId(), p.precioVenta(), p.costo(),
                p.stockMinimo(), p.stockMaximo(), p.leadTimeDias(), p.activo());
    }

    public void update(Producto p) {
        db.update("UPDATE productos SET codigo=?, nombre=?, categoria_id=?, proveedor_id=?, precio_venta=?, costo=?, "
                        + "stock_minimo=?, stock_maximo=?, lead_time_dias=?, activo=? WHERE id=?",
                p.codigo().trim(), p.nombre().trim(), p.categoriaId(), p.proveedorId(), p.precioVenta(), p.costo(),
                p.stockMinimo(), p.stockMaximo(), p.leadTimeDias(), p.activo(), p.id());
    }

    public void delete(long id) {
        db.update("DELETE FROM productos WHERE id = ?", id);
    }

    public long count() {
        return db.queryLong("SELECT COUNT(*) FROM productos");
    }
}
