package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Compra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CompraRepository {

    private static final String SELECT = "SELECT c.id, c.fecha, c.producto_id, p.codigo, p.nombre, c.sucursal_id, "
            + "s.codigo, c.proveedor_id, c.cantidad, c.costo_unitario, c.total, c.estado, c.fecha_esperada, "
            + "c.fecha_recepcion, c.documento, c.origen FROM compras c "
            + "JOIN productos p ON p.id = c.producto_id JOIN sucursales s ON s.id = c.sucursal_id ";

    /** Tiempo de entrega observado de un proveedor (pedido → recepción) en días. */
    public record LeadTimeObservado(long proveedorId, int observaciones, double promedioDias, double desviacionDias) {
    }

    /** Entradas por compras recibidas de un producto en una sucursal y día. */
    public record EntradaDiaria(long productoId, long sucursalId, LocalDate fecha, double cantidad) {
    }

    private final Database db;

    public CompraRepository(Database db) {
        this.db = db;
    }

    private static LocalDate fecha(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    private static Compra map(ResultSet rs) throws SQLException {
        return new Compra(rs.getLong(1), fecha(rs.getString(2)), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getLong(6), rs.getString(7), Database.getNullableLong(rs, "proveedor_id"), rs.getInt(9),
                rs.getDouble(10), rs.getDouble(11), rs.getString(12), fecha(rs.getString(13)),
                fecha(rs.getString(14)), rs.getString(15), rs.getString(16));
    }

    /** Compra recibida en el momento (comportamiento original). */
    public long insert(LocalDate fecha, long productoId, long sucursalId, Long proveedorId, int cantidad,
                       double costoUnitario) {
        return insert(fecha, productoId, sucursalId, proveedorId, cantidad, costoUnitario, Compra.RECIBIDA, null,
                fecha, null, Compra.ORIGEN_SISTEMA, null);
    }

    public long insert(LocalDate fecha, long productoId, long sucursalId, Long proveedorId, int cantidad,
                       double costoUnitario, String estado, LocalDate fechaEsperada, LocalDate fechaRecepcion,
                       String documento, String origen, String huella) {
        return db.insert("INSERT INTO compras(fecha, producto_id, sucursal_id, proveedor_id, cantidad, "
                        + "costo_unitario, total, estado, fecha_esperada, fecha_recepcion, documento, origen, huella) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                fecha, productoId, sucursalId, proveedorId, cantidad, costoUnitario, cantidad * costoUnitario, estado,
                fechaEsperada, fechaRecepcion, documento, origen, huella);
    }

    /** Huella que identifica una línea de orden de compra importada (detección de duplicados). */
    public static String huella(LocalDate fecha, long productoId, long sucursalId, int cantidad, double costo,
                                String documento) {
        return "C|" + fecha + "|" + productoId + "|" + sucursalId + "|" + cantidad + "|" + costo + "|"
                + (documento == null ? "" : documento.trim().toUpperCase());
    }

    public java.util.Optional<Long> idPorHuella(String huella) {
        return db.queryOne("SELECT id FROM compras WHERE huella = ?", rs -> rs.getLong(1), huella);
    }

    public boolean existsHuella(String huella) {
        return db.queryLong("SELECT COUNT(*) FROM compras WHERE huella = ?", huella) > 0;
    }

    /** Total comprometido (compras no canceladas) en un periodo YYYY-MM. */
    public double totalEnPeriodo(String periodo) {
        return db.queryDouble("SELECT COALESCE(SUM(total),0) FROM compras WHERE substr(fecha,1,7) = ? "
                + "AND estado <> 'CANCELADA'", periodo);
    }

    public List<Compra> findRecientes(int limit) {
        return db.query(SELECT + "ORDER BY c.fecha DESC, c.id DESC LIMIT " + Math.max(1, limit), CompraRepository::map);
    }

    public List<Compra> findPendientes() {
        return db.query(SELECT + "WHERE c.estado = 'PENDIENTE' ORDER BY c.fecha_esperada, c.id", CompraRepository::map);
    }

    public Optional<Compra> findById(long id) {
        return db.queryOne(SELECT + "WHERE c.id = ?", CompraRepository::map, id);
    }

    public void marcarRecibida(long id, LocalDate fechaRecepcion, int cantidad) {
        db.update("UPDATE compras SET estado = 'RECIBIDA', fecha_recepcion = ?, cantidad = ?, "
                + "total = ? * costo_unitario WHERE id = ?", fechaRecepcion, cantidad, cantidad, id);
    }

    public void marcarCancelada(long id) {
        db.update("UPDATE compras SET estado = 'CANCELADA' WHERE id = ?", id);
    }

    /** Unidades pendientes de recibir (stock en tránsito) por producto. */
    public Map<Long, Integer> enTransitoPorProducto() {
        Map<Long, Integer> out = new HashMap<>();
        db.query("SELECT producto_id, SUM(cantidad) FROM compras WHERE estado = 'PENDIENTE' GROUP BY producto_id",
                rs -> out.put(rs.getLong(1), rs.getInt(2)));
        return out;
    }

    /** Tiempos de entrega reales por proveedor a partir de las compras recibidas con fecha de pedido y recepción. */
    public Map<Long, LeadTimeObservado> leadTimesObservados() {
        Map<Long, LeadTimeObservado> out = new HashMap<>();
        db.query("SELECT proveedor_id, COUNT(*), AVG(d), AVG(d*d) FROM (SELECT proveedor_id, "
                        + "julianday(fecha_recepcion) - julianday(fecha) AS d FROM compras WHERE estado = 'RECIBIDA' "
                        + "AND proveedor_id IS NOT NULL AND fecha_recepcion IS NOT NULL AND fecha_recepcion > fecha) "
                        + "GROUP BY proveedor_id",
                rs -> {
                    int n = rs.getInt(2);
                    double media = rs.getDouble(3);
                    double var = n > 1 ? Math.max(0, (rs.getDouble(4) - media * media) * n / (n - 1)) : 0;
                    return out.put(rs.getLong(1), new LeadTimeObservado(rs.getLong(1), n, media, Math.sqrt(var)));
                });
        return out;
    }

    /** Fecha del último pedido emitido a cada proveedor (para calcular el próximo ciclo de compra). */
    public Map<Long, LocalDate> ultimoPedidoPorProveedor() {
        Map<Long, LocalDate> out = new HashMap<>();
        db.query("SELECT proveedor_id, MAX(fecha) FROM compras WHERE proveedor_id IS NOT NULL "
                        + "AND estado <> 'CANCELADA' GROUP BY proveedor_id",
                rs -> out.put(rs.getLong(1), fecha(rs.getString(2))));
        return out;
    }

    /** Compras recibidas agregadas por producto, sucursal y día de recepción. */
    public List<EntradaDiaria> recibidasPorDia() {
        return db.query("SELECT producto_id, sucursal_id, substr(COALESCE(fecha_recepcion, fecha),1,10) AS f, "
                        + "SUM(cantidad) FROM compras WHERE estado = 'RECIBIDA' GROUP BY producto_id, sucursal_id, f",
                rs -> new EntradaDiaria(rs.getLong(1), rs.getLong(2), LocalDate.parse(rs.getString(3)),
                        rs.getDouble(4)));
    }

    /** Compras (no canceladas) de un producto emitidas en (desde, hasta]: {unidades, costo total}. */
    public double[] compradoEntre(long productoId, LocalDate desde, LocalDate hasta) {
        return db.queryOne("SELECT COALESCE(SUM(cantidad),0), COALESCE(SUM(total),0) FROM compras WHERE "
                        + "producto_id = ? AND estado <> 'CANCELADA' AND fecha > ? AND fecha <= ?",
                rs -> new double[]{rs.getDouble(1), rs.getDouble(2)}, productoId, desde, hasta)
                .orElse(new double[]{0, 0});
    }
}
