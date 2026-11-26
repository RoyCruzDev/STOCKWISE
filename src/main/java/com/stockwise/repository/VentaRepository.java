package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Venta;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class VentaRepository {
    private static final String SELECT = "SELECT v.id, v.fecha, v.producto_id, p.codigo, p.nombre, v.sucursal_id, "
            + "s.codigo, v.cantidad, v.precio_unitario, v.total, v.referencia FROM ventas v "
            + "JOIN productos p ON p.id = v.producto_id JOIN sucursales s ON s.id = v.sucursal_id ";

    /** Agregado mensual: periodo YYYY-MM, unidades e importe. */
    public record MonthlyTotal(String periodo, double unidades, double importe) {
    }

    /** Fila agregada genérica: clave, unidades vendidas, importe de venta y costo de lo vendido. */
    public record Aggregate(long id, String codigo, String nombre, double unidades, double importe, double costo) {
        public double utilidad() {
            return importe - costo;
        }
    }

    /** Unidades mensuales de un producto. */
    public record ProductMonth(long productoId, String periodo, double unidades) {
    }

    /** Unidades vendidas de un producto en una sucursal. */
    public record ProductBranchUnits(long productoId, long sucursalId, double unidades) {
    }

    /** Resumen de ventas de un producto en ventanas de 30/90/365 días hasta la fecha de corte. */
    public record ProductSummary(long productoId, double u30, double u90, double u365, double importe365,
                                 LocalDate ultimaVenta) {
    }

    private final Database db;

    public List<ProductSummary> resumenPorProducto(LocalDate fechaCorte) {
        return db.query("SELECT producto_id, "
                        + "SUM(CASE WHEN fecha > ? THEN cantidad ELSE 0 END), "
                        + "SUM(CASE WHEN fecha > ? THEN cantidad ELSE 0 END), "
                        + "SUM(CASE WHEN fecha > ? THEN cantidad ELSE 0 END), "
                        + "SUM(CASE WHEN fecha > ? THEN total ELSE 0 END), MAX(fecha) "
                        + "FROM ventas WHERE fecha <= ? GROUP BY producto_id",
                rs -> new ProductSummary(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), LocalDate.parse(rs.getString(6))),
                fechaCorte.minusDays(30), fechaCorte.minusDays(90), fechaCorte.minusDays(365),
                fechaCorte.minusDays(365), fechaCorte);
    }

    public VentaRepository(Database db) {
        this.db = db;
    }

    private static Venta map(ResultSet rs) throws SQLException {
        return new Venta(rs.getLong(1), LocalDate.parse(rs.getString(2)), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getString(7), rs.getDouble(8), rs.getDouble(9),
                rs.getDouble(10), rs.getString(11));
    }

    /** Huella que identifica una venta para detectar duplicados. */
    public static String huella(LocalDate fecha, long productoId, long sucursalId, double cantidad,
                                double precio, String referencia) {
        return fecha + "|" + productoId + "|" + sucursalId + "|" + cantidad + "|" + precio + "|"
                + (referencia == null ? "" : referencia.trim().toUpperCase());
    }

    public java.util.Optional<Long> idPorHuella(String huella) {
        return db.queryOne("SELECT id FROM ventas WHERE huella = ?", rs -> rs.getLong(1), huella);
    }

    public boolean existsHuella(String huella) {
        return db.queryLong("SELECT COUNT(*) FROM ventas WHERE huella = ?", huella) > 0;
    }

    public long insert(LocalDate fecha, long productoId, long sucursalId, double cantidad, double precio,
                       String referencia) {
        return insert(fecha, productoId, sucursalId, cantidad, precio, referencia,
                huella(fecha, productoId, sucursalId, cantidad, precio, referencia));
    }

    /** Inserta con una huella dada (p. ej. la de la segunda línea idéntica de un mismo comprobante). */
    public long insert(LocalDate fecha, long productoId, long sucursalId, double cantidad, double precio,
                       String referencia, String h) {
        return db.insert("INSERT INTO ventas(fecha, producto_id, sucursal_id, cantidad, precio_unitario, total, "
                        + "referencia, huella) VALUES (?,?,?,?,?,?,?,?)",
                fecha, productoId, sucursalId, cantidad, precio, cantidad * precio, referencia, h);
    }

    public Optional<Venta> findById(long id) {
        return db.queryOne(SELECT + "WHERE v.id = ?", VentaRepository::map, id);
    }

    public void delete(long id) {
        db.update("DELETE FROM ventas WHERE id = ?", id);
    }

    public List<Venta> find(LocalDate desde, LocalDate hasta, Long productoId, Long sucursalId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (desde != null) {
            sql.append("AND v.fecha >= ? ");
            params.add(desde);
        }
        if (hasta != null) {
            sql.append("AND v.fecha <= ? ");
            params.add(hasta);
        }
        if (productoId != null) {
            sql.append("AND v.producto_id = ? ");
            params.add(productoId);
        }
        if (sucursalId != null) {
            sql.append("AND v.sucursal_id = ? ");
            params.add(sucursalId);
        }
        sql.append("ORDER BY v.fecha DESC, v.id DESC LIMIT ").append(Math.max(1, limit));
        return db.query(sql.toString(), VentaRepository::map, params.toArray());
    }

    public Optional<LocalDate> fechaMaxima() {
        return db.queryOne("SELECT MAX(fecha) FROM ventas", rs -> rs.getString(1)).map(LocalDate::parse);
    }

    public Optional<LocalDate> fechaMinima() {
        return db.queryOne("SELECT MIN(fecha) FROM ventas", rs -> rs.getString(1)).map(LocalDate::parse);
    }

    public long count() {
        return db.queryLong("SELECT COUNT(*) FROM ventas");
    }

    public double totalImporte(LocalDate desde, LocalDate hasta) {
        return db.queryDouble("SELECT COALESCE(SUM(total),0) FROM ventas WHERE fecha BETWEEN ? AND ?", desde, hasta);
    }

    public double totalUnidades(LocalDate desde, LocalDate hasta) {
        return db.queryDouble("SELECT COALESCE(SUM(cantidad),0) FROM ventas WHERE fecha BETWEEN ? AND ?",
                desde, hasta);
    }

    /** Ventas netas de devoluciones (las devoluciones restan unidades e importe en su mes). */
    static final String NETO = "(SELECT fecha, producto_id, sucursal_id, cantidad, total FROM ventas UNION ALL "
            + "SELECT fecha, producto_id, sucursal_id, -cantidad, -total FROM devoluciones)";

    /** Serie mensual global, o de un producto / sucursal si se indica (ventas netas de devoluciones, mínimo 0). */
    public List<MonthlyTotal> mensual(Long productoId, Long sucursalId) {
        StringBuilder sql = new StringBuilder("SELECT substr(fecha,1,7) AS per, MAX(SUM(cantidad), 0), "
                + "MAX(SUM(total), 0) FROM " + NETO + " WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (productoId != null) {
            sql.append("AND producto_id = ? ");
            params.add(productoId);
        }
        if (sucursalId != null) {
            sql.append("AND sucursal_id = ? ");
            params.add(sucursalId);
        }
        sql.append("GROUP BY per ORDER BY per");
        return db.query(sql.toString(), rs -> new MonthlyTotal(rs.getString(1), rs.getDouble(2), rs.getDouble(3)),
                params.toArray());
    }

    /** Totales mensuales (unidades, importe y costo) en un rango; el código del agregado es el periodo YYYY-MM. */
    public List<Aggregate> mensualConCosto(LocalDate desde, LocalDate hasta) {
        return db.query("SELECT substr(v.fecha,1,7) AS per, SUM(v.cantidad), SUM(v.total), SUM(v.cantidad * p.costo) "
                        + "FROM ventas v JOIN productos p ON p.id = v.producto_id WHERE v.fecha BETWEEN ? AND ? "
                        + "GROUP BY per ORDER BY per",
                rs -> new Aggregate(0, rs.getString(1), rs.getString(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4)), desde, hasta);
    }

    /** Unidades mensuales netas de devoluciones (mínimo 0) de todos los productos. */
    public List<ProductMonth> mensualPorProducto() {
        return db.query("SELECT producto_id, substr(fecha,1,7) AS per, MAX(SUM(cantidad), 0) FROM " + NETO + " "
                        + "GROUP BY producto_id, per ORDER BY producto_id, per",
                rs -> new ProductMonth(rs.getLong(1), rs.getString(2), rs.getDouble(3)));
    }

    /** Unidades vendidas por producto y sucursal en un rango de fechas. */
    public List<ProductBranchUnits> unidadesPorProductoSucursal(LocalDate desde, LocalDate hasta) {
        return db.query("SELECT producto_id, sucursal_id, SUM(cantidad) FROM ventas WHERE fecha BETWEEN ? AND ? "
                        + "GROUP BY producto_id, sucursal_id",
                rs -> new ProductBranchUnits(rs.getLong(1), rs.getLong(2), rs.getDouble(3)), desde, hasta);
    }

    /** Fecha de la primera venta de cada producto. */
    public java.util.Map<Long, LocalDate> primeraVentaPorProducto() {
        java.util.Map<Long, LocalDate> out = new java.util.HashMap<>();
        db.query("SELECT producto_id, MIN(fecha) FROM ventas GROUP BY producto_id",
                rs -> out.put(rs.getLong(1), LocalDate.parse(rs.getString(2).substring(0, 10))));
        return out;
    }

    /** Unidades netas de devoluciones por producto con fecha en (desde, hasta]. */
    public java.util.Map<Long, Double> netoPorProductoEntre(LocalDate desde, LocalDate hasta) {
        java.util.Map<Long, Double> out = new java.util.HashMap<>();
        db.query("SELECT producto_id, MAX(SUM(cantidad), 0) FROM " + NETO + " WHERE fecha > ? AND fecha <= ? "
                + "GROUP BY producto_id", rs -> out.put(rs.getLong(1), rs.getDouble(2)), desde, hasta);
        return out;
    }

    /** Unidades e importe por producto con fecha en (desde, hasta]: productoId → {unidades, importe}. */
    public java.util.Map<Long, double[]> totalesPorProductoEntre(LocalDate desde, LocalDate hasta) {
        java.util.Map<Long, double[]> out = new java.util.HashMap<>();
        db.query("SELECT producto_id, SUM(cantidad), SUM(total) FROM ventas WHERE fecha > ? AND fecha <= ? "
                        + "GROUP BY producto_id",
                rs -> out.put(rs.getLong(1), new double[]{rs.getDouble(2), rs.getDouble(3)}), desde, hasta);
        return out;
    }

    public List<Aggregate> porProducto(LocalDate desde, LocalDate hasta) {
        return db.query("SELECT p.id, p.codigo, p.nombre, COALESCE(SUM(v.cantidad),0), COALESCE(SUM(v.total),0), "
                        + "COALESCE(SUM(v.cantidad * p.costo),0) FROM productos p "
                        + "LEFT JOIN ventas v ON v.producto_id = p.id AND v.fecha BETWEEN ? AND ? "
                        + "GROUP BY p.id ORDER BY 5 DESC",
                rs -> new Aggregate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getDouble(6)), desde, hasta);
    }

    public List<Aggregate> porSucursal(LocalDate desde, LocalDate hasta) {
        return db.query("SELECT s.id, s.codigo, s.nombre, COALESCE(SUM(v.cantidad),0), COALESCE(SUM(v.total),0), "
                        + "COALESCE(SUM(v.cantidad * p.costo),0) FROM sucursales s "
                        + "LEFT JOIN ventas v ON v.sucursal_id = s.id AND v.fecha BETWEEN ? AND ? "
                        + "LEFT JOIN productos p ON p.id = v.producto_id "
                        + "GROUP BY s.id ORDER BY 5 DESC",
                rs -> new Aggregate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getDouble(6)), desde, hasta);
    }

    public List<Aggregate> porCategoria(LocalDate desde, LocalDate hasta) {
        return db.query("SELECT COALESCE(c.id,0), COALESCE(c.nombre,'Sin categoría'), "
                        + "COALESCE(c.nombre,'Sin categoría'), SUM(v.cantidad), SUM(v.total), "
                        + "SUM(v.cantidad * p.costo) FROM ventas v JOIN productos p ON p.id = v.producto_id "
                        + "LEFT JOIN categorias c ON c.id = p.categoria_id "
                        + "WHERE v.fecha BETWEEN ? AND ? GROUP BY c.id ORDER BY 5 DESC",
                rs -> new Aggregate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getDouble(6)), desde, hasta);
    }
}
