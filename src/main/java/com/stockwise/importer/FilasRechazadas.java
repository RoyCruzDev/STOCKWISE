package com.stockwise.importer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Archivo CSV con todas las filas rechazadas de una importación (número de fila, motivo y los valores originales),
 * para que el usuario las revise o corrija en Excel. Se escribe por flujo: no importa cuántas sean. Se crea solo si hay
 * alguna fila rechazada.
 */
public final class FilasRechazadas implements AutoCloseable {

    private final Path destino;
    private final List<String> encabezados;
    private BufferedWriter out;
    private long filas;

    public FilasRechazadas(Path destino, List<String> encabezados) {
        this.destino = destino;
        this.encabezados = encabezados;
    }

    /** Carpeta donde se guardan los archivos de filas rechazadas de un proyecto. */
    public static Path archivo(Path baseDatos, long importacionId) {
        Path dir = baseDatos.toAbsolutePath().getParent().resolve("rechazos");
        return dir.resolve("importacion-" + importacionId + "-filas-rechazadas.csv");
    }

    private static String csv(Object v) {
        String s = v == null ? "" : v instanceof Double d ? ValueParser.texto(d) : v.toString();
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    public void agregar(int numeroFila, String motivo, List<Object> celdas) {
        try {
            if (out == null) {
                Files.createDirectories(destino.getParent());
                out = Files.newBufferedWriter(destino, StandardCharsets.UTF_8);
                out.write('﻿'); // BOM: Excel abre bien las tildes
                StringBuilder h = new StringBuilder("Fila del archivo;Motivo");
                encabezados.forEach(e -> h.append(';').append(csv(e)));
                out.write(h.toString());
                out.newLine();
            }
            StringBuilder sb = new StringBuilder().append(numeroFila).append(';').append(csv(motivo));
            for (int i = 0; i < encabezados.size(); i++) {
                sb.append(';').append(csv(i < celdas.size() ? celdas.get(i) : null));
            }
            out.write(sb.toString());
            out.newLine();
            filas++;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el archivo de filas rechazadas: " + e.getMessage(), e);
        }
    }

    public long filas() {
        return filas;
    }

    /** Archivo generado, o nulo si no hubo filas rechazadas. */
    public Path generado() {
        return filas > 0 ? destino : null;
    }

    @Override
    public void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
