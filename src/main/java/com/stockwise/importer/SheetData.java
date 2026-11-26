package com.stockwise.importer;

import java.util.List;

/**
 * Contenido de una hoja de Excel.
 *
 * @param filaEncabezado número de fila (base 1) donde están los encabezados
 * @param filas          valores de cada fila de datos (String, Double, LocalDate, Boolean o null)
 * @param numerosFila    número de fila en Excel (base 1) de cada fila de datos
 */
public record SheetData(String nombre, int filaEncabezado, List<String> encabezados, List<List<Object>> filas,
                        List<Integer> numerosFila) {

    public int totalFilas() {
        return filas.size();
    }
}
