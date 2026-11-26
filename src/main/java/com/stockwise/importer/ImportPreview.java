package com.stockwise.importer;

import java.util.List;
import java.util.Map;

/** Resultado de validar una hoja antes de importarla. */
public record ImportPreview(ImportType tipo, String hoja, Map<String, Integer> mapeo, List<ImportRow> filas) {

    public long count(ImportRow.Estado e) {
        return filas.stream().filter(r -> r.estado() == e).count();
    }

    public long importables() {
        return filas.stream().filter(ImportRow::importable).count();
    }

    public double tasaError() {
        return filas.isEmpty() ? 0 : count(ImportRow.Estado.ERROR) / (double) filas.size();
    }

    public String resumen() {
        long omitidas = count(ImportRow.Estado.OMITIDA);
        return String.format("%d filas: %d nuevas, %d actualizaciones, %d duplicadas, %d con error%s.", filas.size(),
                count(ImportRow.Estado.NUEVO), count(ImportRow.Estado.ACTUALIZA), count(ImportRow.Estado.DUPLICADO),
                count(ImportRow.Estado.ERROR), omitidas > 0 ? ", " + omitidas + " omitidas (totales/devoluciones)" : "");
    }
}
