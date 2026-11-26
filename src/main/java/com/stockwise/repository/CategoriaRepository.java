package com.stockwise.repository;

import com.stockwise.database.Database;
import com.stockwise.model.Categoria;

import java.util.List;
import java.util.Optional;

public class CategoriaRepository {
    private final Database db;

    public CategoriaRepository(Database db) {
        this.db = db;
    }

    public List<Categoria> findAll() {
        return db.query("SELECT id, nombre FROM categorias ORDER BY nombre",
                rs -> new Categoria(rs.getLong(1), rs.getString(2)));
    }

    public Optional<Categoria> findByNombre(String nombre) {
        return db.queryOne("SELECT id, nombre FROM categorias WHERE nombre = ?",
                rs -> new Categoria(rs.getLong(1), rs.getString(2)), nombre.trim());
    }

    /** Devuelve el id de la categoría, creándola si no existe. Nulo si el nombre está vacío. */
    public Long getOrCreate(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return null;
        }
        return findByNombre(nombre).map(Categoria::id)
                .orElseGet(() -> db.insert("INSERT INTO categorias(nombre) VALUES (?)", nombre.trim()));
    }
}
