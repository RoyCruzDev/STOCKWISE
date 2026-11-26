package com.stockwise.importer;

import com.stockwise.service.ValidationException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector de archivos CSV/TXT exportados por sistemas ERP.
 * <ul>
 *   <li>Detecta el separador ({@code ; , TAB |}) por consistencia entre las primeras líneas.</li>
 *   <li>Detecta la codificación: UTF-8 (con o sin BOM) o, si no es válida, Windows-1252 (típica de Excel/ERP en
 *       español).</li>
 *   <li>Admite campos entre comillas con separadores, comillas dobles escapadas y saltos de línea.</li>
 *   <li>Detecta la fila de encabezados (omite títulos del reporte) igual que el lector de Excel.</li>
 * </ul>
 * El archivo se devuelve como una única hoja con el nombre del archivo (sin extensión), que se usa para sugerir el
 * tipo de información.
 */
public final class CsvReader {

    private static final char[] SEPARADORES = {';', ',', '\t', '|'};
    private static final int FILAS_BUSQUEDA_ENCABEZADO = 15;

    private CsvReader() {
    }

    public static List<SheetData> leer(Path archivo) {
        if (archivo == null || !Files.isRegularFile(archivo)) {
            throw new ValidationException("El archivo no existe.");
        }
        String texto;
        try {
            texto = decodificar(Files.readAllBytes(archivo));
        } catch (IOException e) {
            throw new ValidationException("No se pudo leer el archivo: " + e.getMessage());
        }
        String nombre = archivo.getFileName().toString();
        int punto = nombre.lastIndexOf('.');
        SheetData hoja = parsear(punto > 0 ? nombre.substring(0, punto) : nombre, texto);
        if (hoja == null) {
            throw new ValidationException("El archivo CSV no contiene datos tabulares (se requieren encabezados y al "
                    + "menos dos columnas).");
        }
        return List.of(hoja);
    }

    static String decodificar(byte[] bytes) {
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            s = new String(bytes, Charset.forName("windows-1252"));
        }
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    /** Separador más consistente en las primeras 20 líneas no vacías. */
    static char detectarSeparador(String texto) {
        String[] lineas = texto.split("\\r?\\n", 60);
        char mejor = ';';
        int mejorPuntaje = -1;
        int mejorModa = 0;
        for (char sep : SEPARADORES) {
            Map<Integer, Integer> frecuencia = new HashMap<>();
            int usadas = 0;
            for (String l : lineas) {
                if (l.isBlank() || usadas >= 20) {
                    continue;
                }
                usadas++;
                frecuencia.merge(contarFueraDeComillas(l, sep), 1, Integer::sum);
            }
            int moda = 0;
            int veces = 0;
            for (Map.Entry<Integer, Integer> e : frecuencia.entrySet()) {
                if (e.getKey() > 0 && (e.getValue() > veces || (e.getValue() == veces && e.getKey() > moda))) {
                    moda = e.getKey();
                    veces = e.getValue();
                }
            }
            if (veces > mejorPuntaje || (veces == mejorPuntaje && moda > mejorModa)) {
                mejor = sep;
                mejorPuntaje = veces;
                mejorModa = moda;
            }
        }
        return mejor;
    }

    private static int contarFueraDeComillas(String l, char sep) {
        int n = 0;
        boolean comillas = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') {
                comillas = !comillas;
            } else if (c == sep && !comillas) {
                n++;
            }
        }
        return n;
    }

    /** Divide el texto en filas y campos respetando comillas. */
    static List<List<String>> filas(String texto, char sep) {
        List<List<String>> out = new ArrayList<>();
        List<String> fila = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (comillas) {
                if (c == '"') {
                    if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                        campo.append('"');
                        i++;
                    } else {
                        comillas = false;
                    }
                } else {
                    campo.append(c);
                }
            } else if (c == '"') {
                comillas = true;
            } else if (c == sep) {
                fila.add(campo.toString());
                campo.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                fila.add(campo.toString());
                campo.setLength(0);
                out.add(fila);
                fila = new ArrayList<>();
            } else {
                campo.append(c);
            }
        }
        if (campo.length() > 0 || !fila.isEmpty()) {
            fila.add(campo.toString());
            out.add(fila);
        }
        return out;
    }

    static SheetData parsear(String nombre, String texto) {
        List<List<String>> filas = filas(texto, detectarSeparador(texto));
        int header = -1;
        for (int r = 0; r < Math.min(filas.size(), FILAS_BUSQUEDA_ENCABEZADO); r++) {
            long textos = filas.get(r).stream().filter(v -> !v.isBlank() && !esNumero(v)).count();
            if (textos >= 2) {
                header = r;
                break;
            }
        }
        if (header < 0) {
            return null;
        }
        List<String> enc = new ArrayList<>();
        List<String> h = filas.get(header);
        for (int c = 0; c < h.size(); c++) {
            String v = h.get(c).trim();
            enc.add(v.isEmpty() ? "Columna " + (c + 1) : v);
        }
        List<List<Object>> datos = new ArrayList<>();
        List<Integer> numeros = new ArrayList<>();
        for (int r = header + 1; r < filas.size(); r++) {
            List<String> f = filas.get(r);
            List<Object> valores = new ArrayList<>(enc.size());
            boolean vacia = true;
            for (int c = 0; c < enc.size(); c++) {
                String v = c < f.size() ? f.get(c).trim() : "";
                valores.add(v.isEmpty() ? null : v);
                vacia &= v.isEmpty();
            }
            if (!vacia) {
                datos.add(valores);
                numeros.add(r + 1);
                if (datos.size() > ExcelReader.MAX_FILAS) {
                    throw new ValidationException("El archivo supera el máximo de " + ExcelReader.MAX_FILAS
                            + " filas por importación.");
                }
            }
        }
        return new SheetData(nombre, header + 1, enc, datos, numeros);
    }

    private static boolean esNumero(String v) {
        return v.trim().matches("[-+]?[0-9.,\\s]+");
    }
}
