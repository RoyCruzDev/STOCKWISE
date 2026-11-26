package com.stockwise.importer;

import org.apache.poi.ss.usermodel.DateUtil;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Conversión tolerante de valores de celdas a tipos del sistema. Lanza IllegalArgumentException si no es posible. */
public final class ValueParser {


    private ValueParser() {
    }

    public static String texto(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : String.valueOf(d);
        }
        if (v instanceof LocalDate ld) {
            return ld.toString();
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public static Double numero(Object v) {
        return numero(v, false);
    }

    /**
     * @param comaDecimal el archivo usa coma decimal y punto de miles (formato latino: 1.234,50): "1.500" se lee como
     *                    mil quinientos. Se deduce de las columnas numéricas del archivo.
     */
    public static Double numero(Object v, boolean comaDecimal) {
        if (v == null) {
            return null;
        }
        if (v instanceof String str && !(v instanceof Double)) {
            String t = str.trim();
            if (t.isEmpty()) {
                return null;
            }
            String digitos = t.replaceAll("[^0-9,.\\-]", "");
            if (digitos.matches("-?\\d{1,3}(\\.\\d{3}){2,}")
                    || (comaDecimal && digitos.indexOf(',') < 0 && digitos.matches("-?\\d{1,3}(\\.\\d{3})+"))) {
                // Solo puntos de miles: 1.234.567 (siempre) o 1.500 en un archivo con coma decimal.
                return numero(t.replace(".", ""), false);
            }
            if (comaDecimal && digitos.indexOf('.') < 0 && digitos.matches("-?\\d+,\\d+")) {
                return numero(t.replace(',', '.'), false);
            }
        }
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        String crudo = v.toString().trim();
        if (crudo.startsWith("(") && crudo.endsWith(")")) {
            // Formato contable: (12.50) = -12.50
            Double d = numero(crudo.substring(1, crudo.length() - 1));
            return d == null ? null : -d;
        }
        String s = v.toString().trim().replaceAll("[^0-9,.\\-]", "");
        if (s.isEmpty() || s.equals("-")) {
            throw new IllegalArgumentException("'" + v + "' no es un número");
        }
        int coma = s.lastIndexOf(',');
        int punto = s.lastIndexOf('.');
        if (coma >= 0 && punto >= 0) {
            // El separador que aparece al final es el decimal.
            s = coma > punto ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (coma >= 0) {
            // Solo comas: decimal si hay 1-2 dígitos después de la última coma y una sola coma.
            boolean decimal = s.indexOf(',') == coma && s.length() - coma - 1 <= 2;
            s = decimal ? s.replace(',', '.') : s.replace(",", "");
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + v + "' no es un número");
        }
    }

    public static Integer entero(Object v) {
        Double d = numero(v);
        if (d == null) {
            return null;
        }
        if (Math.abs(d - Math.rint(d)) > 1e-9) {
            throw new IllegalArgumentException("'" + texto(v) + "' debe ser un número entero");
        }
        return (int) Math.rint(d);
    }

    public static LocalDate fecha(Object v) {
        return fecha(v, false);
    }

    private static final java.util.regex.Pattern HORA = java.util.regex.Pattern.compile(
            "^(.+?)[ T]+\\d{1,2}:\\d{2}(:\\d{2}([.,]\\d+)?)?\\s*([aApP]\\.?\\s?[mM]\\.?)?\\s*(z|[+-]\\d{2}:?\\d{2})?$");
    private static final java.util.Map<String, Integer> MESES = meses();

    private static java.util.Map<String, Integer> meses() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        String[][] n = {{"ene", "enero", "jan", "january"}, {"feb", "febrero", "february"}, {"mar", "marzo", "march"},
                {"abr", "abril", "apr", "april"}, {"may", "mayo"}, {"jun", "junio", "june"}, {"jul", "julio", "july"},
                {"ago", "agosto", "aug", "august"}, {"sep", "set", "sept", "septiembre", "setiembre", "september"},
                {"oct", "octubre", "october"}, {"nov", "noviembre", "november"}, {"dic", "diciembre", "dec", "december"}};
        for (int i = 0; i < n.length; i++) {
            for (String s : n[i]) {
                m.put(s, i + 1);
            }
        }
        return m;
    }

    /**
     * Fecha en los formatos habituales de reportes: AAAA-MM-DD, DD/MM/AAAA, DD-MM-AA, DD.MM.AAAA, AAAAMMDD, con hora
     * opcional ("6/11/2026 15:19:20", "2025-01-31T10:00"), con el mes en texto en español o inglés ("15-ene-2025",
     * "15 de enero de 2025", "January 31, 2025") y número de serie de Excel.
     *
     * @param mesPrimero en fechas ambiguas como 03/04/2025, interpretar el primer número como mes (formato EE. UU.);
     *                   se deduce de la columna completa. Si un número es mayor que 12 se usa el orden evidente.
     */
    public static LocalDate fecha(Object v, boolean mesPrimero) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.time.LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (v instanceof Double d) {
            if (d > 20000 && d < 80000) {
                return DateUtil.getLocalDateTime(d).toLocalDate();
            }
            throw new IllegalArgumentException("'" + texto(v) + "' no es una fecha válida");
        }
        String original = v.toString().trim();
        if (original.isEmpty()) {
            return null;
        }
        String s = original;
        java.util.regex.Matcher h = HORA.matcher(s);
        if (h.matches()) {
            s = h.group(1).trim();
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ignored) {
            // formatos alternativos
        }
        if (s.matches("\\d{8}")) {
            try {
                return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException ignored) {
                // continuar
            }
        }
        if (s.matches("\\d{5}(\\.\\d+)?")) {
            double serie = Double.parseDouble(s);
            if (serie > 20000 && serie < 80000) {
                return DateUtil.getLocalDateTime(serie).toLocalDate();
            }
        }
        // Mes en texto: se reemplaza por su número y se recuerda si venía antes que el día.
        String t = ColumnMapper.normalizar(s).replaceAll("\\b(de|del)\\b", " ").trim();
        List<String> partes = new java.util.ArrayList<>();
        int posMesTexto = -1;
        for (String tok : t.split(" +")) {
            if (tok.isEmpty()) {
                continue;
            }
            if (tok.matches("\\d+")) {
                partes.add(tok);
            } else if (MESES.containsKey(tok)) {
                posMesTexto = partes.size();
                partes.add(String.valueOf(MESES.get(tok)));
            } else {
                throw new IllegalArgumentException("'" + original + "' no es una fecha válida");
            }
        }
        if (partes.size() != 3) {
            throw new IllegalArgumentException("'" + original + "' no es una fecha válida");
        }
        int a = Integer.parseInt(partes.get(0));
        int b = Integer.parseInt(partes.get(1));
        int c = Integer.parseInt(partes.get(2));
        int anio;
        int mes;
        int dia;
        if (partes.get(0).length() == 4) {
            anio = a;
            mes = posMesTexto == 2 ? c : b;
            dia = posMesTexto == 2 ? b : c;
        } else {
            anio = partes.get(2).length() <= 2 ? (c < 70 ? 2000 + c : 1900 + c) : c;
            if (posMesTexto == 0) {
                mes = a;
                dia = b;
            } else if (posMesTexto == 1) {
                dia = a;
                mes = b;
            } else if (a > 12) {
                dia = a;
                mes = b;
            } else if (b > 12) {
                mes = a;
                dia = b;
            } else if (mesPrimero) {
                mes = a;
                dia = b;
            } else {
                dia = a;
                mes = b;
            }
        }
        try {
            return LocalDate.of(anio, mes, dia);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("'" + original + "' no es una fecha válida");
        }
    }

    /**
     * Normaliza el estado de una orden de compra de un reporte ERP a PENDIENTE, RECIBIDA o CANCELADA.
     *
     * @return estado normalizado o nulo si el texto está vacío
     */
    public static String estadoCompra(Object v) {
        String s = ColumnMapper.normalizar(texto(v));
        if (s.isEmpty()) {
            return null;
        }
        if (s.matches(".*(anulad|cancelad|rechazad|eliminad).*")) {
            return "CANCELADA";
        }
        if (s.matches(".*(pendiente|transito|por recibir|abierta|emitida|aprobada|en camino|solicitad|parcial).*")) {
            return "PENDIENTE";
        }
        if (s.matches(".*(recibid|cerrad|atendid|completad|ingresad|entregad|finalizad).*")) {
            return "RECIBIDA";
        }
        throw new IllegalArgumentException("'" + v + "' no es un estado reconocido (use Pendiente, Recibida o Anulada)");
    }

    public static YearMonth periodo(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return YearMonth.from(d);
        }
        String s = texto(v);
        try {
            return YearMonth.parse(s);
        } catch (DateTimeParseException e) {
            // continuar
        }
        try {
            return YearMonth.parse(s, DateTimeFormatter.ofPattern("M/yyyy"));
        } catch (DateTimeParseException e) {
            // continuar
        }
        try {
            return YearMonth.from(fecha(v));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + s + "' no es un periodo válido (use AAAA-MM)");
        }
    }
}
