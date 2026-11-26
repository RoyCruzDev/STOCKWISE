package com.stockwise.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agrupa los errores de una importación por causa, para explicar en pocas líneas por qué se rechazaron filas
 * (p. ej. "124 634 filas: el producto no existe — 3 000 productos distintos, ej.: GASEOSA X, GALLETA Y") en lugar de
 * un número de errores sin explicación.
 */
public final class ResumenErrores {

    private static final Pattern VALOR = Pattern.compile("'([^']*)'");
    private static final int MAX_DISTINTOS = 100_000;
    private static final int EJEMPLOS = 3;

    private final Map<String, Long> filas = new LinkedHashMap<>();
    private final Map<String, Set<String>> valores = new HashMap<>();
    private final Map<String, List<String>> ejemplos = new HashMap<>();
    private final Map<String, Integer> primeraFila = new HashMap<>();

    /** Registra los mensajes de una fila con error (las demás se ignoran). */
    public void agregar(ImportRow r) {
        if (r.estado() != ImportRow.Estado.ERROR) {
            return;
        }
        for (String m : r.mensajes()) {
            Matcher x = VALOR.matcher(m);
            String causa = x.replaceAll("'…'").replaceAll("\\d{4}-\\d{2}-\\d{2}", "(fecha)");
            filas.merge(causa, 1L, Long::sum);
            primeraFila.putIfAbsent(causa, r.numeroFila());
            x.reset();
            if (x.find()) {
                Set<String> s = valores.computeIfAbsent(causa, k -> new HashSet<>());
                String v = x.group(1);
                if (s.size() < MAX_DISTINTOS && s.add(v)) {
                    List<String> e = ejemplos.computeIfAbsent(causa, k -> new ArrayList<>());
                    if (e.size() < EJEMPLOS) {
                        e.add(v);
                    }
                }
            }
        }
    }

    public static ResumenErrores de(List<ImportRow> filas) {
        ResumenErrores r = new ResumenErrores();
        filas.forEach(r::agregar);
        return r;
    }

    public boolean vacio() {
        return filas.isEmpty();
    }

    /** Filas afectadas por una causa que contiene el texto indicado (p. ej. "producto"). */
    public long filas(String contiene) {
        return filas.entrySet().stream().filter(e -> e.getKey().contains(contiene)).mapToLong(Map.Entry::getValue)
                .sum();
    }

    /** Valores distintos (p. ej. productos) de las causas que contienen el texto indicado. */
    public int distintos(String contiene) {
        return valores.entrySet().stream().filter(e -> e.getKey().contains(contiene)).mapToInt(e -> e.getValue().size())
                .sum();
    }

    /** Hasta {@code max} causas, de la más a la menos frecuente, en lenguaje sencillo. */
    public List<String> lineas(int max) {
        List<String> out = new ArrayList<>();
        filas.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(max)
                .forEach(e -> {
                    String c = e.getKey();
                    StringBuilder sb = new StringBuilder(String.format("%,d fila(s): %s", e.getValue(),
                            c.replace(" '…'", "")));
                    Set<String> v = valores.get(c);
                    if (v != null && !v.isEmpty()) {
                        sb.append(String.format(" — %,d%s distinto(s), ej.: %s", v.size(),
                                v.size() >= MAX_DISTINTOS ? "+" : "", String.join(", ", ejemplos.get(c))));
                    } else {
                        sb.append(" (primera: fila ").append(primeraFila.get(c)).append(')');
                    }
                    out.add(sb.toString());
                });
        return out;
    }

    /** Qué puede hacer el usuario según las causas encontradas. */
    public List<String> sugerencias() {
        List<String> out = new ArrayList<>();
        if (filas("producto") > 0) {
            out.add("Productos no encontrados: revise que la columna \"Código de producto\" contenga el mismo código que "
                    + "su catálogo (o relacione también \"Nombre del producto\"), importe primero los productos, o "
                    + "marque \"Crear productos y sucursales que no existan\".");
        }
        if (filas("sucursal") > 0) {
            out.add("Sucursales no encontradas: registre las sucursales, quite la relación de la columna Sucursal "
                    + "(se usará la principal) o marque \"Crear productos y sucursales que no existan\".");
        }
        if (filas("fecha") > 0) {
            out.add("Fechas no válidas o futuras: verifique la columna relacionada como Fecha.");
        }
        if (filas("obligatori") > 0 || filas("vacío") > 0 || filas("número") > 0) {
            out.add("Valores vacíos o con formato incorrecto: revise la relación de columnas.");
        }
        return out;
    }

    /** Texto completo para guardar en el historial. */
    public String texto() {
        List<String> l = new ArrayList<>(lineas(8));
        List<String> s = sugerencias();
        if (!s.isEmpty()) {
            l.add("");
            l.add("Qué puede hacer:");
            s.forEach(x -> l.add("• " + x));
        }
        return String.join("\n", l);
    }
}
