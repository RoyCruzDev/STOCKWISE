package com.stockwise.exporter;

import java.util.List;

/**
 * Tabla de reporte independiente del formato de salida.
 *
 * @param tipos     tipo de cada columna, para dar formato y alineación
 * @param resumen   líneas de resumen mostradas bajo el título (pueden estar vacías)
 */
public record ReportTable(String titulo, String subtitulo, List<String> columnas, List<ColumnType> tipos,
                          List<List<Object>> filas, List<String> resumen) {

    public enum ColumnType {
        TEXTO, ENTERO, DECIMAL, MONEDA, PORCENTAJE
    }

    public ReportTable {
        if (columnas.size() != tipos.size()) {
            throw new IllegalArgumentException("Columnas y tipos deben tener el mismo tamaño");
        }
        for (List<Object> f : filas) {
            if (f.size() != columnas.size()) {
                throw new IllegalArgumentException("Cada fila debe tener " + columnas.size() + " valores");
            }
        }
    }
}
