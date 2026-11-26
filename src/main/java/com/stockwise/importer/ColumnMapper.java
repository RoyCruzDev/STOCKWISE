package com.stockwise.importer;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Relaciona automáticamente encabezados del Excel con campos del sistema. */
public final class ColumnMapper {

    private ColumnMapper() {
    }

    /** Minúsculas, sin tildes, sin signos; espacios simples. */
    public static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return n.replaceAll("\\s+", " ");
    }

    /**
     * Devuelve clave de campo -> índice de columna. Primero busca coincidencias exactas y después
     * coincidencias por contención; cada columna se asigna como máximo a un campo.
     */
    public static Map<String, Integer> sugerir(ImportType tipo, List<String> encabezados) {
        Map<String, Integer> mapping = new LinkedHashMap<>();
        Set<Integer> usadas = new HashSet<>();
        List<String> norm = encabezados.stream().map(ColumnMapper::normalizar).toList();
        // Pasada 1: coincidencia exacta con la clave, la etiqueta o un sinónimo, respetando el orden de preferencia
        // de los sinónimos (p. ej. en ventas "SKU" o "Código de barra" antes que "Producto", que suele ser el nombre).
        for (FieldDef f : tipo.campos()) {
            List<String> candidatos = new java.util.ArrayList<>();
            candidatos.add(normalizar(f.etiqueta()));
            f.sinonimos().forEach(s -> candidatos.add(normalizar(s)));
            candidatos.add(normalizar(f.clave()));
            buscar:
            for (String c : candidatos) {
                for (int i = 0; i < norm.size(); i++) {
                    if (!usadas.contains(i) && !norm.get(i).isEmpty() && norm.get(i).equals(c)) {
                        mapping.put(f.clave(), i);
                        usadas.add(i);
                        break buscar;
                    }
                }
            }
        }
        // Pasada 2: el encabezado contiene un sinónimo (p. ej. "Precio Unitario S/").
        for (FieldDef f : tipo.campos()) {
            if (mapping.containsKey(f.clave())) {
                continue;
            }
            outer:
            for (int i = 0; i < norm.size(); i++) {
                if (usadas.contains(i) || norm.get(i).isEmpty()) {
                    continue;
                }
                for (String sin : f.sinonimos()) {
                    String s = normalizar(sin);
                    if (s.length() >= 3 && (" " + norm.get(i) + " ").contains(" " + s + " ")) {
                        mapping.put(f.clave(), i);
                        usadas.add(i);
                        break outer;
                    }
                }
            }
        }
        return mapping;
    }
}
