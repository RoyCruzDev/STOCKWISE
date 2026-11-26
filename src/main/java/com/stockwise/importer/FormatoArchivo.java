package com.stockwise.importer;

import com.stockwise.importer.FieldDef.FieldType;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convenciones de un archivo que no se pueden saber mirando un solo valor y se deducen de la muestra completa:
 * <ul>
 *   <li><b>Orden de las fechas</b>: 03/04/2025 es 3 de abril (día primero) salvo que en la columna aparezca una fecha
 *       como 04/13/2025, que solo es válida con el mes primero (formato de EE. UU.).</li>
 *   <li><b>Separador decimal</b>: si los números usan coma decimal (12,50 o 1.234,50), un valor como "1.500" es mil
 *       quinientos y no uno y medio.</li>
 * </ul>
 */
public record FormatoArchivo(boolean mesPrimero, boolean comaDecimal) {

    public static final FormatoArchivo POR_DEFECTO = new FormatoArchivo(false, false);

    private static final Pattern FECHA = Pattern.compile("^\\s*(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})");
    private static final Pattern COMA_DECIMAL = Pattern.compile("^-?[^0-9]*\\d{1,3}(\\.\\d{3})*,\\d{1,2}[^0-9]*$");
    private static final Pattern PUNTO_DECIMAL = Pattern.compile("^-?[^0-9]*\\d{1,3}(,\\d{3})*\\.\\d{1,2}[^0-9]*$");

    /** Deduce el formato a partir de las filas de muestra y de las columnas relacionadas con cada campo. */
    public static FormatoArchivo deducir(List<List<Object>> muestra, ImportType tipo, Map<String, Integer> mapeo) {
        int diaEvidente = 0;
        int mesEvidente = 0;
        int coma = 0;
        int punto = 0;
        for (FieldDef f : tipo.campos()) {
            Integer col = mapeo.get(f.clave());
            if (col == null) {
                continue;
            }
            for (List<Object> fila : muestra) {
                Object v = col < fila.size() ? fila.get(col) : null;
                if (!(v instanceof String s) || s.isBlank()) {
                    continue;
                }
                if (f.tipo() == FieldType.FECHA) {
                    Matcher m = FECHA.matcher(s);
                    if (m.find()) {
                        int a = Integer.parseInt(m.group(1));
                        int b = Integer.parseInt(m.group(2));
                        if (a > 12 && b <= 12) {
                            diaEvidente++;
                        } else if (b > 12 && a <= 12) {
                            mesEvidente++;
                        }
                    }
                } else if (f.tipo() == FieldType.NUMERO || f.tipo() == FieldType.ENTERO) {
                    String t = s.trim();
                    if (COMA_DECIMAL.matcher(t).matches()) {
                        coma++;
                    } else if (PUNTO_DECIMAL.matcher(t).matches()) {
                        punto++;
                    }
                }
            }
        }
        return new FormatoArchivo(mesEvidente > 0 && diaEvidente == 0, coma > 0 && punto == 0);
    }

    /** Descripción para el usuario (vacía si es el formato habitual). */
    public String descripcion() {
        StringBuilder sb = new StringBuilder();
        if (mesPrimero) {
            sb.append("Fechas con el mes primero (MM/DD/AAAA). ");
        }
        if (comaDecimal) {
            sb.append("Números con coma decimal y punto de miles (1.234,50). ");
        }
        return sb.toString().trim();
    }
}
