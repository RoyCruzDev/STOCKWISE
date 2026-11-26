package com.stockwise.importer;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Genera la plantilla oficial de importación: una hoja por tipo con sus encabezados e instrucciones. */
public final class TemplateGenerator {

    private TemplateGenerator() {
    }

    public static void generar(Path destino) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle titulo = estiloTitulo(wb);
            CellStyle obligatorio = estiloEncabezado(wb, IndexedColors.DARK_BLUE);
            CellStyle opcional = estiloEncabezado(wb, IndexedColors.GREY_50_PERCENT);

            Sheet ins = wb.createSheet("Instrucciones");
            int r = 0;
            Row t = ins.createRow(r++);
            t.createCell(0).setCellValue("STOCKWISE - Plantilla oficial de importación");
            t.getCell(0).setCellStyle(titulo);
            String[] lineas = {
                    "",
                    "1. Complete cada hoja a partir de la fila 2. No cambie los encabezados de la fila 1.",
                    "2. Columnas en azul: obligatorias. Columnas en gris: opcionales.",
                    "3. Orden recomendado de importación: Sucursales → Proveedores → Productos → Inventario → Ventas → "
                            + "Órdenes de compra → Presupuestos.",
                    "   También puede importar reportes del ERP en .xls o .csv: el sistema detecta encabezados, "
                            + "separador y codificación, omite filas de totales y recuerda la relación de columnas.",
                    "4. Fechas en formato AAAA-MM-DD o DD/MM/AAAA (o celdas con formato de fecha).",
                    "5. Periodos de presupuesto en formato AAAA-MM (ej. 2025-03).",
                    "6. Si no indica sucursal en Inventario o Ventas se asigna la sucursal PRINCIPAL.",
                    "7. Los productos y sucursales se identifican por su código; si ya existen se actualizan.",
                    "8. Las ventas idénticas (misma fecha, producto, sucursal, cantidad, precio y referencia) se "
                            + "detectan como duplicadas y no se importan dos veces.",
                    "9. Órdenes de compra: Estado Pendiente = en tránsito (se descuenta de las necesidades de compra); "
                            + "Recibida = histórica. Las órdenes importadas no modifican el stock actual.",
                    "",
                    "Detalle de campos:"};
            for (String l : lineas) {
                ins.createRow(r++).createCell(0).setCellValue(l);
            }
            for (ImportType tipo : ImportType.values()) {
                ins.createRow(r++).createCell(0).setCellValue("• " + tipo.etiqueta());
                for (FieldDef f : tipo.campos()) {
                    ins.createRow(r++).createCell(0).setCellValue("     " + f.etiqueta()
                            + (f.obligatorio() ? " (obligatorio)" : " (opcional)")
                            + (f.descripcion().isBlank() ? "" : " - " + f.descripcion()));
                }
            }
            ins.setColumnWidth(0, 120 * 256);

            for (ImportType tipo : ImportType.values()) {
                Sheet sh = wb.createSheet(tipo.etiqueta());
                Row h = sh.createRow(0);
                for (int i = 0; i < tipo.campos().size(); i++) {
                    FieldDef f = tipo.campos().get(i);
                    h.createCell(i).setCellValue(f.etiqueta());
                    h.getCell(i).setCellStyle(f.obligatorio() ? obligatorio : opcional);
                    sh.setColumnWidth(i, Math.max(14, f.etiqueta().length() + 4) * 256);
                }
                sh.createFreezePane(0, 1);
            }
            Files.createDirectories(destino.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(destino)) {
                wb.write(out);
            }
        }
    }

    static CellStyle estiloEncabezado(Workbook wb, IndexedColors color) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        st.setFont(f);
        st.setFillForegroundColor(color.getIndex());
        st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return st;
    }

    static CellStyle estiloTitulo(Workbook wb) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        f.setColor(IndexedColors.DARK_BLUE.getIndex());
        st.setFont(f);
        return st;
    }
}
