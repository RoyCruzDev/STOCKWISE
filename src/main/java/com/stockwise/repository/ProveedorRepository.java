package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Proveedor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class ProveedorRepository {
    private final Database db;

    public ProveedorRepository(Database db) {
        this.db = db;
    }

    private static Integer entero(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : (int) v;
    }

    private static Proveedor map(ResultSet rs) throws SQLException {
        return new Proveedor(rs.getLong("id"), rs.getString("nombre"), rs.getString("contacto"),
                rs.getString("telefono"), rs.getString("email"), entero(rs, "lead_time_dias"),
                entero(rs, "ciclo_compra_dias"));
    }

    public List<Proveedor> findAll() {
        return db.query("SELECT * FROM proveedores ORDER BY nombre", ProveedorRepository::map);
    }

    public Optional<Proveedor> findById(long id) {
        return db.queryOne("SELECT * FROM proveedores WHERE id = ?", ProveedorRepository::map, id);
    }

    public Optional<Proveedor> findByNombre(String nombre) {
        return db.queryOne("SELECT * FROM proveedores WHERE nombre = ?", ProveedorRepository::map, nombre.trim());
    }

    public Long getOrCreate(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return null;
        }
        return findByNombre(nombre).map(Proveedor::id)
                .orElseGet(() -> db.insert("INSERT INTO proveedores(nombre) VALUES (?)", nombre.trim()));
    }

    public long insert(Proveedor p) {
        return db.insert("INSERT INTO proveedores(nombre, contacto, telefono, email, lead_time_dias, "
                        + "ciclo_compra_dias) VALUES (?,?,?,?,?,?)", p.nombre().trim(), p.contacto(), p.telefono(),
                p.email(), p.leadTimeDias(), p.cicloCompraDias());
    }

    public void update(Proveedor p) {
        db.update("UPDATE proveedores SET nombre=?, contacto=?, telefono=?, email=?, lead_time_dias=?, "
                        + "ciclo_compra_dias=? WHERE id=?", p.nombre().trim(), p.contacto(), p.telefono(), p.email(),
                p.leadTimeDias(), p.cicloCompraDias(), p.id());
    }
}
