package com.stockwise.exporter;

import com.stockwise.exporter.ReportTable.ColumnType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** Exporta una o varias tablas de reporte a un libro Excel con formato profesional. */
public final class ExcelExporter {

    private ExcelExporter() {
    }

    public static void exportar(List<ReportTable> tablas, Path destino, String empresa, String simboloMoneda)
            throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            DataFormat df = wb.createDataFormat();
            CellStyle titulo = wb.createCellStyle();
            Font ft = wb.createFont();
            ft.setBold(true);
            ft.setFontHeightInPoints((short) 14);
            ft.setColor(IndexedColors.DARK_BLUE.getIndex());
            titulo.setFont(ft);

            CellStyle header = wb.createCellStyle();
            Font fh = wb.createFont();
            fh.setBold(true);
            fh.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(fh);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            CellStyle texto = wb.createCellStyle();
            CellStyle entero = wb.createCellStyle();
            entero.setDataFormat(df.getFormat("#,##0"));
            CellStyle decimal = wb.createCellStyle();
            decimal.setDataFormat(df.getFormat("#,##0.00"));
            CellStyle moneda = wb.createCellStyle();
            String sim = simboloMoneda == null ? "" : simboloMoneda.replace("\"", "");
            moneda.setDataFormat(df.getFormat("\"" + sim + " \"#,##0.00"));
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(df.getFormat("0.0%"));
            CellStyle fecha = wb.createCellStyle();
            fecha.setDataFormat(df.getFormat("dd/mm/yyyy"));

            for (ReportTable t : tablas) {
                String nombre = nombreHoja(wb, t.titulo());
                Sheet sh = wb.createSheet(nombre);
                int r = 0;
                Row rt = sh.createRow(r++);
                rt.createCell(0).setCellValue(t.titulo());
                rt.getCell(0).setCellStyle(titulo);
                sh.createRow(r++).createCell(0).setCellValue(empresa + " · " + t.subtitulo());
                for (String linea : t.resumen()) {
                    sh.createRow(r++).createCell(0).setCellValue(linea);
                }
                r++;
                int headerRow = r;
                Row h = sh.createRow(r++);
                for (int c = 0; c < t.columnas().size(); c++) {
                    Cell cell = h.createCell(c);
                    cell.setCellValue(t.columnas().get(c));
                    cell.setCellStyle(header);
                }
                for (List<Object> fila : t.filas()) {
                    Row row = sh.createRow(r++);
                    for (int c = 0; c < fila.size(); c++) {
                        Object v = fila.get(c);
                        Cell cell = row.createCell(c);
                        if (v == null) {
                            continue;
                        }
                        ColumnType tipo = t.tipos().get(c);
                        if (v instanceof Number n) {
                            cell.setCellValue(n.doubleValue());
                            cell.setCellStyle(switch (tipo) {
                                case ENTERO -> entero;
                                case MONEDA -> moneda;
                                case PORCENTAJE -> pct;
                                default -> decimal;
                            });
                        } else if (v instanceof LocalDate d) {
                            cell.setCellValue(d);
                            cell.setCellStyle(fecha);
                        } else {
                            cell.setCellValue(v.toString());
                            cell.setCellStyle(texto);
                        }
                    }
                }
                if (!t.filas().isEmpty()) {
                    sh.setAutoFilter(new CellRangeAddress(headerRow, r - 1, 0, t.columnas().size() - 1));
                }
                sh.createFreezePane(0, headerRow + 1);
                for (int c = 0; c < t.columnas().size(); c++) {
                    int max = t.columnas().get(c).length();
                    for (List<Object> fila : t.filas()) {
                        Object v = fila.get(c);
                        if (v != null) {
                            max = Math.max(max, Math.min(60, v.toString().length()));
                        }
                    }
                    sh.setColumnWidth(c, Math.min(255, max + 4) * 256);
                }
            }
            Path parent = destino.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(destino)) {
                wb.write(out);
            }
        }
    }

    private static String nombreHoja(XSSFWorkbook wb, String titulo) {
        String base = titulo.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (base.length() > 28) {
            base = base.substring(0, 28);
        }
        String nombre = base;
        int i = 2;
        while (wb.getSheet(nombre) != null) {
            nombre = base + " " + i++;
        }
        return nombre;
    }
}
