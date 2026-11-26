package com.stockwise.importer;

import com.stockwise.service.Progreso;
import com.stockwise.service.ValidationException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;

import java.nio.file.Path;
import java.util.List;

/**
 * Lee archivos Excel (.xlsx, .xls) o CSV/TXT completos en memoria (para archivos de tamaño normal), detectando la
 * fila de encabezados. La lectura se hace por flujo con {@link LectorStreaming}: nunca se carga el libro XLSX con el
 * modelo de objetos de POI. Los archivos con más de {@link #MAX_FILAS} filas deben importarse por bloques
 * ({@code ImportService.importarPorBloques}).
 */
public final class ExcelReader {

    /** Máximo de filas por hoja para la lectura completa en memoria. */
    public static final int MAX_FILAS = 500_000;

    private ExcelReader() {
    }

    public static List<SheetData> leer(Path archivo) {
        List<LectorStreaming.Hoja> hojas = LectorStreaming.inspeccionar(archivo, MAX_FILAS, Progreso.NINGUNO);
        for (LectorStreaming.Hoja h : hojas) {
            if (!h.completa()) {
                throw new ValidationException(String.format("La hoja '%s' tiene %,d filas: supera el máximo de %,d para "
                        + "leerla completa en memoria. Use la importación por bloques.", h.nombre(), h.totalFilas(),
                        MAX_FILAS));
            }
        }
        return hojas.stream().map(LectorStreaming.Hoja::muestra).toList();
    }

    /** Valor de una celda del modelo de POI (formato .xls). */
    static Object valor(Cell c) {
        if (c == null) {
            return null;
        }
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return switch (t) {
            case STRING -> {
                String s = c.getStringCellValue().trim();
                yield s.isEmpty() ? null : s;
            }
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                    ? c.getLocalDateTimeCellValue().toLocalDate()
                    : (Object) c.getNumericCellValue();
            case BOOLEAN -> c.getBooleanCellValue();
            default -> null;
        };
    }
}
