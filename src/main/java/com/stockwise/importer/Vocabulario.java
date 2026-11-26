package com.stockwise.importer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Nombres de columna conocidos (de todos los tipos de importación), para reconocer la fila de encabezados. */
final class Vocabulario {

    private static final Set<String> TERMINOS = construir();

    private Vocabulario() {
    }

    private static Set<String> construir() {
        Set<String> s = new HashSet<>();
        for (ImportType t : ImportType.values()) {
            for (FieldDef f : t.campos()) {
                s.add(ColumnMapper.normalizar(f.etiqueta()));
                s.add(ColumnMapper.normalizar(f.clave().replace('_', ' ')));
                f.sinonimos().forEach(x -> s.add(ColumnMapper.normalizar(x)));
            }
        }
        s.remove("");
        return s;
    }

    /**
     * Puntaje de una fila como encabezado: 2 por cada celda que es exactamente un nombre conocido y 1 si lo contiene
     * como palabra (p. ej. "Precio Unitario S/"). Las celdas con dos puntos ("Empresa: X") no puntúan: son datos.
     */
    static int puntos(List<Object> celdas) {
        int p = 0;
        for (Object c : celdas) {
            if (!(c instanceof String s) || s.isBlank() || s.contains(":") || s.length() > 40) {
                continue;
            }
            String n = ColumnMapper.normalizar(s);
            if (n.isEmpty()) {
                continue;
            }
            if (TERMINOS.contains(n)) {
                p += 2;
                continue;
            }
            String conEspacios = " " + n + " ";
            for (String t : TERMINOS) {
                if (t.length() >= 4 && conEspacios.contains(" " + t + " ")) {
                    p += 1;
                    break;
                }
            }
        }
        return p;
    }
}
