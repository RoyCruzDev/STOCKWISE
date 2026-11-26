package com.stockwise.importer;

public record ImportResult(ImportType tipo, int insertados, int actualizados, int omitidos) {

    public String resumen() {
        return String.format("%s: %d registros nuevos, %d actualizados, %d omitidos.", tipo.etiqueta(), insertados,
                actualizados, omitidos);
    }
}
