# STOCKWISE

Aplicación de escritorio para analizar ventas e inventarios, predecir la demanda y recomendar compras y
acciones comerciales. Funciona **100 % offline**: no usa servicios de IA, APIs externas ni servidores. Todo el
procesamiento (estadística, predicción y motor de reglas) se ejecuta localmente en Java.

## Requisitos

| Para…                        | Necesita                                                        |
|------------------------------|-----------------------------------------------------------------|
| Usar el ejecutable generado  | Windows 10/11. No requiere Java ni Internet (incluye su runtime). |
| Compilar desde el código     | JDK 21 (con `jpackage`) y Maven 3.9+. Internet solo la primera vez, para descargar dependencias. |

Tecnologías: Java 21, JavaFX 21 (FXML + CSS), SQLite (sqlite-jdbc), Apache POI, Apache Commons Math,
Apache PDFBox (exportación PDF), JUnit 5.

## Compilar y ejecutar

```powershell
# Defina el JDK 21 a usar
$env:JAVA_HOME = "C:\ruta\a\jdk-21"

mvn clean package          # compila, ejecuta las 143 pruebas y genera target/stockwise.jar + target/libs
java -jar target/stockwise.jar   # ejecuta la aplicación
# o bien
mvn javafx:run
```

Solo pruebas: `mvn test`.

## Generar el ejecutable / instalador (Windows)

```powershell
$env:JAVA_HOME = "C:\ruta\a\jdk-21"
.\scripts\empaquetar.ps1                 # carpeta portable: target\installer\STOCKWISE\STOCKWISE.exe
.\scripts\empaquetar.ps1 -Tipo exe       # instalador .exe (requiere WiX Toolset 3.x en el PATH)
.\scripts\empaquetar.ps1 -OmitirPruebas  # empaqueta sin ejecutar las pruebas
```

La carpeta `target\installer\STOCKWISE` incluye su propio runtime de Java (≈200 MB) y puede copiarse a
cualquier PC con Windows. Una vez generada, la aplicación no necesita conexión.

## Datos locales

- Ejecutando desde la carpeta del proyecto: `./data/` (catálogo `proyectos.db`, bases de cada proyecto, `logs/`,
  `backups/`).
- Ejecutable instalado: `%USERPROFILE%\STOCKWISE\data`.
- Otra ubicación: opción de Java `-Dstockwise.data=RUTA`.

**Proyectos**: cada proyecto (p. ej. Ropa, Alimentos, Electrónica) tiene su **propia base SQLite** en
`data/proyectos/<id>-<nombre>/stockwise.db`; los datos de dos proyectos nunca se mezclan. La base de versiones anteriores
(`data/stockwise.db`) se registra automáticamente como *Proyecto principal* sin moverla. Al eliminar un proyecto se
exige escribir su nombre y antes se guarda una copia en `backups/proyectos-eliminados`. Las bases y sus tablas se
crean y migran solas; antes de migrar una base con datos de una versión anterior se guarda una copia en
`backups/pre-migracion-v<versión>-<fecha>.db`.

## Primeros pasos

1. Al abrir STOCKWISE **cree o seleccione un proyecto**.
2. En **Inicio** pulse *Importar archivo* (ventas, compras, inventario o productos; Excel o CSV). El sistema detecta
   encabezados, reconoce las columnas (y reutiliza la relación de una importación anterior con la misma estructura),
   valida, marca repetidos y muestra una vista previa. Puede importar mes a mes: el historial se acumula.
3. Pulse **Analizar**: una pantalla muestra el avance real por etapas (se puede cancelar sin guardar nada) y al
   terminar abre **Resultados del análisis**: qué comprar y cuánto, qué mantener, qué no volver a comprar, qué
   pierde demanda y qué podría agotarse, rankings y el detalle de predicción de cada producto.

## Módulos

| Módulo | Qué hace |
|---|---|
| Inicio | Estado del proyecto (ventas, meses de historial, importaciones, último análisis), recorrido guiado Importar → Analizar → Resultados y aviso cuando hay datos nuevos sin analizar. |
| Resultados del análisis | Indicadores para el gerente (productos analizados, mayor demanda, demanda creciente, riesgo de agotarse, exceso, sin movimiento, compra recomendada); **recomendaciones de compra** con la explicación y los datos de cada una; **rankings** (más vendidos, mayor crecimiento, para recomprar, menos vendidos, olvidados, no recomprar); **detalle por producto** (historia, real vs. predicción, pronóstico a 7/15/30/60/90 días con intervalo del 80 %, modelos comparados y métricas del backtesting); **comparación entre análisis** (precisión real del análisis anterior con las ventas que llegaron después, cambios de demanda, patrón y recomendación). Consulta de análisis anteriores y exportación a Excel. |
| Panel principal | KPIs de ventas, inventario valorizado, predicción del próximo mes, top de productos, ventas por categoría, riesgo de agotamiento, sobreinventario, baja rotación y alertas. |
| Importar Excel / CSV | Lectura de .xlsx y .xls (POI) y de .csv/.txt de sistemas ERP (separador y codificación UTF-8/Windows-1252 automáticos, campos entre comillas), detección de encabezados bajo títulos de reporte, mapeo de columnas con sinónimos ERP y **perfiles de mapeo recordados por formato de reporte**, omisión de filas de totales y de devoluciones (cantidades negativas), importación de **proveedores** (tiempo de entrega y ciclo de compra) y **órdenes de compra** (pendientes = stock en tránsito; recibidas = históricas), validación por tipo de dato, duplicados en archivo y en base de datos, vista previa, exportación de observaciones, guardado transaccional (todo o nada). Plantilla oficial y archivo de demostración. |
| Productos | Alta, edición, baja (con confirmación), filtro por categoría, precio, costo, margen, historial de ventas e inventario por sucursal. |
| Inventario | Stock por sucursal, mínimos/máximos, rotación, cobertura, inventario valorizado, alertas, ajustes, entradas, salidas, traslados e historial de movimientos. |
| Ventas | Consulta filtrada, registro manual (opcionalmente descuenta stock) y eliminación. |
| Predicción de demanda | Promedio móvil, promedio móvil ponderado, suavizamiento exponencial y regresión lineal; **clasificación ADI/CV²** (suave, errática, intermitente, irregular) y **modelos especializados** LightGBM, CatBoost y Croston-TSB que compiten con los clásicos; comparación con MAE y MAPE (validación *walk-forward*), selección automática, tendencia, confianza, advertencias y gráfico real vs. predicción a 1, 3 y 6 meses. |
| Compras y presupuesto | Punto de reorden y **nivel objetivo** sobre la posición de inventario (stock + **tránsito**), stock de seguridad con variabilidad de la demanda y del **tiempo de entrega observado**, **ciclos de compra por proveedor**, ROI esperado por línea, pedidos en tránsito con **recepción** (parcial) y cancelación, cantidades sugeridas, asignación por prioridad **sin superar nunca el presupuesto**, registro de órdenes de compra y gestión de presupuestos por periodo. |
| Asesor estratégico | Motor de reglas: traslados entre sucursales, promociones, negociación con proveedores, liquidación, ajuste de precios, reducción de compras, prevención de sobreinventario y desabastecimiento, productos rentables y poco rentables. Cada recomendación incluye problema, acción, justificación y beneficio estimado. |
| Planificación avanzada | Herramientas de análisis detallado: patrones de demanda ADI/CV²; **stock histórico diario reconstruido**; **simulación what-if** (demanda, presupuesto, tiempos de entrega, nivel de servicio); **scorecard** de calidad del análisis; **ROI** del plan y GMROI; **impacto real** de decisiones guardadas; **backtesting walk-forward** contra pronóstico ingenuo (WAPE, sesgo, FVA). Exportación a Excel. |
| Sucursales | Registro, inventario por sucursal, comparación de rendimiento y traslados sugeridos ejecutables. |
| Historial de datos | Archivos importados (fecha, periodo, registros nuevos y repetidos, estado) y análisis realizados. **Eliminar una importación** muestra antes qué se afecta y borra solo los registros que pertenecen exclusivamente a ella (los que también están en otra importación, los registrados a mano y los anteriores al historial se conservan). **Eliminar un análisis** borra solo sus resultados, nunca los datos históricos. |
| Reportes | Ventas históricas, predicción, inventario, más vendidos, baja rotación, compras, rentabilidad (ABC), sucursales y recomendaciones; exportación a Excel y PDF. |
| Configuración | Empresa, moneda, presupuesto general, parámetros de predicción e inventario, margen mínimo, tema claro/oscuro, copias de seguridad y restauración. |

## Criterios de cálculo principales

- **Fecha de corte**: última venta registrada. Si cae a mitad de mes, ese mes parcial se excluye de la serie de
  predicción para no subestimar la demanda.
- **Datos insuficientes**: con menos de 3 meses de historial no se genera pronóstico. Bajo el mínimo
  configurable (6 meses por defecto), con demanda intermitente o alta variabilidad se muestran advertencias.
- **Selección del método**: menor MAE en validación *walk-forward* (todos los métodos evaluados desde el mismo
  periodo); el MAPE ignora meses sin ventas.
- **Motor de predicción**: por producto compiten los 4 métodos clásicos, el modelo de referencia *ingenuo* y, según el
  diagnóstico, Holt amortiguado (tendencia), Holt-Winters e ingenuo estacional (autocorrelación a 12 meses ≥ 0,3 con
  24+ meses), Croston-TSB (intermitente), LightGBM/CatBoost (con ≥ 6 meses de validación) y la *combinación de los 3
  mejores* (elegidos sin mirar el futuro). Todos se evalúan con backtesting walk-forward en el **mismo periodo**,
  entrenando con las ventas atípicas moderadas (residuo > 3,5 · 1,4826 · MAD sobre la tendencia) y midiendo el error
  contra las ventas reales. Se selecciona por MAE (RMSE en demanda intermitente) con **parsimonia**: entre modelos a
  menos del 2 % del mejor se elige el más simple. Métricas: MAE, RMSE, WAPE, MAPE (sin meses en cero), sesgo y valor
  agregado frente al ingenuo (FVA). La demanda es **neta de devoluciones**.
- **Horizontes**: demanda a 7, 15, 30, 60 y 90 días prorrateando el pronóstico mensual por días; intervalo del 80 % =
  ± 1,2816 · RMSE fuera de muestra · √(días / 30,4). Sin error medible no se informa intervalo; sin historial
  suficiente no se informa pronóstico.
- **Patrón de demanda (ADI/CV²)**: ADI = meses / meses con venta; CV² = (σ/μ)² de las ventas no nulas; cortes
  1,32 y 0,49 (Syntetos-Boylan). Suave → LightGBM; errática → CatBoost; intermitente → Croston-TSB; irregular →
  Croston-TSB y CatBoost. Los modelos especializados exigen historial suficiente (GBDT: 20 meses; Croston-TSB:
  6) y compiten por MAE con los clásicos en el mismo periodo de validación. LightGBM (árboles por hojas sobre
  histogramas) y CatBoost (árboles simétricos con estadísticos de objetivo ordenados) son implementaciones
  propias en Java de esos algoritmos, sin bibliotecas nativas.
- **Compras**: `SS = z · √(LT · σd² + d² · σLT²)` (σLT = desviación observada del tiempo de entrega del proveedor
  con ≥ 3 pedidos recibidos; 0 si no hay datos), `ROP = d · LT + SS`, `objetivo = d · (LT + R) + SS` con R = ciclo
  de compra del proveedor (u horizonte configurado; tope: stock máximo). Se compara la **posición** = stock +
  órdenes pendientes. Tiempo de reposición: el del producto, o el del proveedor (configurado u observado), o el
  valor por defecto. El presupuesto se asigna por prioridad (crítica → baja) y contribución; las compras
  parciales se ajustan al saldo disponible. ROI esperado = margen de lo que se venderá en LT + R / inversión.
- **Stock histórico**: `stock(d−1) = stock(d) − entradas(d) + salidas(d)` desde el inventario actual (ventas,
  compras recibidas, ajustes, traslados; la carga inicial importada es una medición). Stock negativo = faltan
  registros. Sin compras/entradas registradas la disponibilidad histórica se informa como *no disponible*.
- **Decisiones**: reglas ordenadas (datos insuficientes → descontinuar → liquidar → negociar → recomprar →
  reducir → publicitar → mantener); ver `recommendation/DecisionEngine`. Los impactos solo usan costos, precios
  y demanda registrados; si faltan, se dejan vacíos.
- **Backtesting**: walk-forward del proceso completo (clasificación + selección), WAPE, sesgo y FVA frente al
  pronóstico ingenuo (último mes). **Scorecard**: cobertura de historial, precisión y sesgo (backtest 3 meses),
  datos maestros, salud del inventario y disponibilidad; los componentes sin datos no cuentan en el puntaje.
- **Estados de inventario**: agotado; riesgo (stock ≤ mínimo o cobertura < tiempo de reposición);
  sin movimiento (sin ventas en N días); sobreinventario (stock > máximo o cobertura > umbral); normal.

## Arquitectura

```
src/main/java/com/stockwise/
  Main.java, StockWiseApp.java   arranque (Main no extiende Application para ejecutar con JavaFX en classpath)
  controller/    controladores MVC de cada vista FXML y ventana principal (Shell)
  model/         entidades de dominio (records)
  database/      conexión SQLite, transacciones (ThreadLocal) y creación del esquema
  repository/    acceso a datos SQL por entidad
  service/       reglas de negocio, validaciones y orquestación (AppContext = inyección de dependencias)
  analytics/     métricas por producto, clasificación de inventario y estadística (Commons Math)
  prediction/    métodos de pronóstico (clásicos, Croston-TSB, LightGBM, CatBoost), ADI/CV², evaluador y motor
  recommendation/ plan de compras, reglas del asesor, decisiones estratégicas y simulación what-if
  importer/      lectores Excel/CSV, mapeo de columnas, validación, plantillas y datos de demostración
  exporter/      exportación de reportes a Excel (POI) y PDF (PDFBox)
  util/          formato, registro de errores, diálogos, tablas, gráficos, iconos
src/main/resources/
  views/ (FXML)  css/ (tema claro y oscuro)  images/  db/schema.sql
src/test/java/   pruebas unitarias y de integración (JUnit 5)
scripts/empaquetar.ps1   generación del ejecutable/instalador con jpackage
```

Seguridad y confiabilidad: validación de datos en servicios e importador, transacciones con *rollback*,
claves foráneas con borrado en cascada, confirmación antes de eliminar, registro de errores en `data/logs`,
copias de seguridad consistentes (`VACUUM INTO`) con copia automática antes de restaurar, y todas las
operaciones pesadas en segundo plano para no bloquear la interfaz.

## Pruebas

`mvn test` ejecuta 143 pruebas: métodos de predicción y errores MAE/MAPE, motor de predicción con datos
insuficientes, clasificación y alertas de inventario, recomendaciones de compra y restricción presupuestaria,
reglas del asesor (incluidos traslados entre sucursales), almacenamiento y transacciones en SQLite,
importación de Excel (duplicados, errores, mapeo, formatos), exportación de reportes a Excel/PDF y copias de
seguridad, más un flujo completo de extremo a extremo. Planificación avanzada: clasificación ADI/CV²,
Croston-TSB, LightGBM y CatBoost, selección especializada; stock de seguridad con σLT, tránsito, ciclos y ROI;
las 7 decisiones estratégicas y el caso sin datos; what-if; reconstrucción de stock; importación ERP (CSV
Windows-1252, XLS, totales, devoluciones, órdenes de compra, proveedores, perfiles de mapeo); órdenes en
tránsito, recepción y cancelación; tiempos de entrega observados; backtesting, scorecard, impacto real de
decisiones y migración de bases de versiones anteriores sin pérdida de datos. Fase de proyectos: modelos de referencia, Holt, Holt-Winters,
detección de estacionalidad y atípicos, parsimonia, combinación sin mirar el futuro, métricas y horizontes; el motor
nuevo frente al anterior en escenarios estacionales; aislamiento, renombrado y eliminación de proyectos;
importación acumulativa por meses, periodos superpuestos, devoluciones, cambio de estructura y eliminación segura de
importaciones (registros compartidos, manuales y transferencia de propiedad); corrida de análisis con avance real,
cancelación sin guardar, rankings, comparación entre análisis y copia de seguridad antes de migrar.

`src/test/java/com/stockwise/ui/ScreenshotTool.java` es una herramienta de desarrollo que carga datos de
demostración y guarda capturas PNG de cada módulo.
