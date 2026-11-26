package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Sucursal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class SucursalRepository {
    private final Database db;

    public SucursalRepository(Database db) {
        this.db = db;
    }

    private static Sucursal map(ResultSet rs) throws SQLException {
        return new Sucursal(rs.getLong("id"), rs.getString("codigo"), rs.getString("nombre"),
                rs.getString("ciudad"), rs.getString("direccion"), rs.getInt("activa") == 1);
    }

    public List<Sucursal> findAll() {
        return db.query("SELECT * FROM sucursales ORDER BY codigo", SucursalRepository::map);
    }

    public List<Sucursal> findActivas() {
        return db.query("SELECT * FROM sucursales WHERE activa = 1 ORDER BY codigo", SucursalRepository::map);
    }

    public Optional<Sucursal> findById(long id) {
        return db.queryOne("SELECT * FROM sucursales WHERE id = ?", SucursalRepository::map, id);
    }

    /** Busca por código o, si no coincide, por nombre (sin distinguir mayúsculas). */
    public Optional<Sucursal> findByCodigoOrNombre(String valor) {
        if (valor == null || valor.isBlank()) {
            return Optional.empty();
        }
        String v = valor.trim();
        return db.queryOne("SELECT * FROM sucursales WHERE codigo = ? COLLATE NOCASE "
                        + "OR nombre = ? COLLATE NOCASE ORDER BY (codigo = ? COLLATE NOCASE) DESC LIMIT 1",
                SucursalRepository::map, v, v, v);
    }

    public long insert(Sucursal s) {
        return db.insert("INSERT INTO sucursales(codigo, nombre, ciudad, direccion, activa) VALUES (?,?,?,?,?)",
                s.codigo().trim(), s.nombre().trim(), s.ciudad(), s.direccion(), s.activa());
    }

    public void update(Sucursal s) {
        db.update("UPDATE sucursales SET codigo=?, nombre=?, ciudad=?, direccion=?, activa=? WHERE id=?",
                s.codigo().trim(), s.nombre().trim(), s.ciudad(), s.direccion(), s.activa(), s.id());
    }

    public void delete(long id) {
        db.update("DELETE FROM sucursales WHERE id = ?", id);
    }
}
