package com.stockwise.importer;

import com.stockwise.repository.ConfigRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plantillas de importación reutilizables: guardan qué columna corresponde a cada campo <b>por el nombre del
 * encabezado</b> (no por su posición), así se aplican a reportes con las columnas en otro orden, con columnas nuevas o
 * con alguna menos. Cada importación guarda automáticamente la plantilla de su formato; el usuario puede además
 * guardarla con un nombre (p. ej. "Ventas del ERP").
 */
public class PlantillasImportacion {

    /** Fracción mínima de las columnas de la plantilla que debe tener un archivo para considerarlo parecido. */
    public static final double COBERTURA_MINIMA = 0.7;
    private static final String PREFIJO = "importacion.plantilla.";

    /**
     * Plantilla guardada.
     *
     * @param campos campo → encabezado normalizado
     */
    public record Plantilla(String id, String nombre, ImportType tipo, Map<String, String> campos, boolean conNombre) {
        @Override
        public String toString() {
            return nombre;
        }
    }

    /** Resultado de buscar la plantilla más parecida a un archivo. */
    public record Coincidencia(Plantilla plantilla, Map<String, Integer> mapeo, double cobertura) {
    }

    private final ConfigRepository config;

    public PlantillasImportacion(ConfigRepository config) {
        this.config = config;
    }

    private static String slug(String s) {
        String n = ColumnMapper.normalizar(s).replace(' ', '-');
        return n.isEmpty() ? "sin-nombre" : n.length() > 60 ? n.substring(0, 60) : n;
    }

    /**
     * Guarda la plantilla. Sin nombre se guarda como plantilla automática del formato (identificada por sus
     * encabezados); con nombre, como plantilla con nombre que el usuario puede elegir.
     */
    public Plantilla guardar(ImportType tipo, String nombre, List<String> encabezados, Map<String, Integer> mapeo) {
        Map<String, String> campos = new LinkedHashMap<>();
        mapeo.forEach((campo, col) -> {
            if (col != null && col >= 0 && col < encabezados.size()) {
                String h = ColumnMapper.normalizar(encabezados.get(col));
                if (!h.isEmpty()) {
                    campos.put(campo, h);
                }
            }
        });
        boolean conNombre = nombre != null && !nombre.isBlank();
        String id = conNombre ? "n-" + slug(nombre)
                : "a-" + Integer.toHexString(String.join("|", campos.values()).hashCode());
        String visible = conNombre ? nombre.trim().replace(";", ",").replace("=", "-")
                : "Formato usado antes (" + campos.size() + " columnas)";
        StringBuilder sb = new StringBuilder("nombre=").append(visible);
        campos.forEach((k, v) -> sb.append(';').append(k).append('=').append(v));
        config.set(PREFIJO + tipo.name() + "." + id, sb.toString());
        return new Plantilla(id, visible, tipo, campos, conNombre);
    }

    /** Plantillas del tipo: primero las que tienen nombre, luego las automáticas. */
    public List<Plantilla> listar(ImportType tipo) {
        List<Plantilla> out = new ArrayList<>();
        String pre = PREFIJO + tipo.name() + ".";
        for (Map.Entry<String, String> e : config.all().entrySet()) {
            if (!e.getKey().startsWith(pre)) {
                continue;
            }
            String id = e.getKey().substring(pre.length());
            String nombre = id;
            Map<String, String> campos = new LinkedHashMap<>();
            for (String par : e.getValue().split(";")) {
                int i = par.indexOf('=');
                if (i <= 0) {
                    continue;
                }
                String k = par.substring(0, i);
                String v = par.substring(i + 1);
                if (k.equals("nombre")) {
                    nombre = v;
                } else if (tipo.campos().stream().anyMatch(f -> f.clave().equals(k))) {
                    campos.put(k, v);
                }
            }
            if (!campos.isEmpty()) {
                out.add(new Plantilla(id, nombre, tipo, campos, id.startsWith("n-")));
            }
        }
        out.sort(Comparator.comparing((Plantilla p) -> !p.conNombre()).thenComparing(Plantilla::nombre));
        return out;
    }

    public void eliminar(Plantilla p) {
        config.set(PREFIJO + p.tipo().name() + "." + p.id(), "");
    }

    /** Relación de columnas de la plantilla en un archivo (solo los campos cuyo encabezado existe). */
    public static Map<String, Integer> aplicar(Plantilla p, List<String> encabezados) {
        List<String> norm = encabezados.stream().map(ColumnMapper::normalizar).toList();
        Map<String, Integer> m = new LinkedHashMap<>();
        p.campos().forEach((campo, h) -> {
            int i = norm.indexOf(h);
            if (i >= 0 && !m.containsValue(i)) {
                m.put(campo, i);
            }
        });
        return m;
    }

    /**
     * Plantilla más parecida al archivo: la que tiene la mayor parte de sus columnas en él (al menos
     * {@value #COBERTURA_MINIMA}) y todas las obligatorias del tipo. Ante empate gana la que tiene nombre.
     */
    public Optional<Coincidencia> masParecida(ImportType tipo, List<String> encabezados) {
        Coincidencia mejor = null;
        for (Plantilla p : listar(tipo)) {
            Map<String, Integer> m = aplicar(p, encabezados);
            double cobertura = m.size() / (double) p.campos().size();
            boolean obligatorios = tipo.campos().stream().filter(FieldDef::obligatorio)
                    .allMatch(f -> m.containsKey(f.clave()));
            if (!obligatorios || cobertura < COBERTURA_MINIMA) {
                continue;
            }
            if (mejor == null || cobertura > mejor.cobertura() + 1e-9
                    || (Math.abs(cobertura - mejor.cobertura()) < 1e-9 && p.conNombre() && !mejor.plantilla().conNombre())) {
                mejor = new Coincidencia(p, m, cobertura);
            }
        }
        return Optional.ofNullable(mejor);
    }
}
