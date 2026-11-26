package com.stockwise.repository;

import com.stockwise.database.Database;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ConfigRepository {
    private final Database db;

    public ConfigRepository(Database db) {
        this.db = db;
    }

    public Optional<String> get(String clave) {
        return db.queryOne("SELECT valor FROM configuracion WHERE clave = ?", rs -> rs.getString(1), clave);
    }

    public void set(String clave, String valor) {
        db.update("INSERT INTO configuracion(clave, valor) VALUES (?, ?) "
                + "ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor", clave, valor);
    }

    public Map<String, String> all() {
        Map<String, String> map = new LinkedHashMap<>();
        db.query("SELECT clave, valor FROM configuracion ORDER BY clave", rs -> {
            map.put(rs.getString(1), rs.getString(2));
            return Boolean.TRUE;
        });
        return map;
    }
}
