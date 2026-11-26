package com.stockwise.importer;

import java.util.List;

/**
 * Campo del sistema que puede recibir una columna del Excel.
 *
 * @param sinonimos encabezados alternativos reconocidos automáticamente (se comparan normalizados)
 */
public record FieldDef(String clave, String etiqueta, boolean obligatorio, FieldType tipo, List<String> sinonimos,
                       String descripcion) {

    public enum FieldType {
        TEXTO, NUMERO, ENTERO, FECHA, PERIODO
    }

    @Override
    public String toString() {
        return etiqueta + (obligatorio ? " *" : "");
    }
}
