package br.com.fmarcatti.leitorcodigobarras

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.hardware.camera2.CaptureRequest
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    data class CatalogProduct(val internalCode: String, val reference: String, val barcode: String, val description: String, val unit: String)
    data class ScanRecord(val code: String, val location: String, val quantity: Int, val format: String,
                          val internalCode: String = "", val description: String = "", val unit: String = "")

    private var companyKey = ""
    private var companyName = ""
    private val prefs by lazy { getSharedPreferences("scanner_data_$companyKey", MODE_PRIVATE) }
    private val records = mutableListOf<ScanRecord>()
    private val products = mutableListOf<CatalogProduct>()
    private lateinit var titleBar: TextView
    private lateinit var previewView: PreviewView
    private lateinit var countText: TextView
    private lateinit var lastText: TextView
    private lateinit var locationText: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    // Confirma a mesma leitura em vários quadros antes de registrar.
    private var candidateCode = ""
    private var candidateFormat = Barcode.FORMAT_UNKNOWN
    private var candidateHits = 0
    private var lastSeenBarcodeAt = 0L
    private var lastAcceptedCode = ""
    private var lastAcceptedAt = 0L
    private var scanning = false
    private var torchOn = false
    private var countMode = false
    private var currentLocation = ""
    private var toneGenerator: ToneGenerator? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Permissão da câmera necessária para ler códigos.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        toneGenerator = try { ToneGenerator(AudioManager.STREAM_MUSIC, 85) } catch (_: Exception) { null }
        buildUi()
        chooseCompany()
    }

    private fun chooseCompany() {
        val labels = arrayOf("Dubraz Elétrica", "Agro Cubatão")
        AlertDialog.Builder(this).setTitle("Selecione a empresa")
            .setItems(labels) { _, which ->
                companyKey = if (which == 0) "dubraz" else "agro"
                companyName = labels[which]
                loadRecords()
                offerLegacyMigration()
                loadCatalog(if (which == 0) "EAN - DUBRAZ.csv" else "EAN - AGRO.csv")
                titleBar.text = "LEITOR DE CÓDIGOS — $companyName"
                refreshList()
                askLocation {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }.setCancelable(false).show()
    }

    private fun offerLegacyMigration() {
        if (records.isNotEmpty()) return
        val legacy = getSharedPreferences("scanner_data", MODE_PRIVATE)
        val json = legacy.getString("records_json", null)
        val oldCodes = legacy.getString("codes", "") ?: ""
        if (json.isNullOrBlank() && oldCodes.isBlank()) return
        AlertDialog.Builder(this).setTitle("Inventário anterior encontrado")
            .setMessage("A lista anterior não identifica a empresa. Deseja importar os registros para $companyName? Escolha esta opção apenas se essa era a empresa da lista antiga.")
            .setNegativeButton("Manter separado", null)
            .setPositiveButton("Importar para $companyName") { _, _ ->
                try {
                    if (!json.isNullOrBlank()) {
                        val array = JSONArray(json)
                        for (i in 0 until array.length()) {
                            val o = array.getJSONObject(i)
                            records.add(ScanRecord(o.optString("code"), o.optString("location", "Não informado"), o.optInt("quantity", 1), o.optString("format", "Desconhecido")))
                        }
                    } else records.addAll(oldCodes.split("\n").filter { it.isNotEmpty() }.map { ScanRecord(it, "Não informado", 1, "Versão anterior") })
                    saveRecords()
                    legacy.edit().remove("records_json").remove("codes").apply()
                    refreshList()
                    Toast.makeText(this, "Inventário anterior importado para $companyName.", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    showNotice("Falha na importação", "Não foi possível ler a lista anterior. Ela foi mantida sem alterações.")
                }
            }.show()
    }

    private fun loadCatalog(assetName: String) {
        products.clear()
        try {
            assets.open(assetName).use { input ->
                BufferedReader(InputStreamReader(input, Charset.forName("windows-1252"))).use { reader ->
                    val first = reader.readLine()?.removePrefix("\uFEFF") ?: return
                    val delimiter = if (first.count { it == ';' } >= first.count { it == ',' }) ';' else ','
                    val header = parseCsvRow(first, delimiter).map { normalizeHeader(it) }
                    fun column(vararg names: String): Int = names.asSequence().map { normalizeHeader(it) }
                        .map { header.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
                    val codeIx = column("Código", "Codigo", "Código interno")
                    val refIx = column("Cód. Referência", "Cod Referencia", "Referência")
                    val eanIx = column("Código Barras", "Codigo Barras", "EAN", "GTIN")
                    val descIx = column("Descrição", "Descricao", "Produto")
                    val unitIx = column("Unidade", "Un")
                    if (codeIx < 0 || descIx < 0) throw IllegalArgumentException("Cabeçalhos obrigatórios Código e Descrição não encontrados.")
                    reader.forEachLine { line ->
                        val row = parseCsvRow(line, delimiter)
                        fun value(index: Int) = if (index >= 0 && index < row.size) row[index].trim() else ""
                        val product = CatalogProduct(value(codeIx), value(refIx), value(eanIx), value(descIx), value(unitIx))
                        if (product.internalCode.isNotEmpty() || product.description.isNotEmpty()) products.add(product)
                    }
                }
            }
            Toast.makeText(this, "Catálogo $companyName carregado: ${products.size} produtos.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            showNotice("Falha ao carregar catálogo", "Não foi possível abrir $assetName. ${e.localizedMessage ?: "Verifique o arquivo."}")
        }
    }

    private fun normalizeHeader(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun parseCsvRow(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"') { cell.append('"'); i++ }
            else if (ch == '"') quoted = !quoted
            else if (ch == delimiter && !quoted) { result.add(cell.toString()); cell.setLength(0) }
            else cell.append(ch)
            i++
        }
        result.add(cell.toString()); return result
    }

    private fun productsFor(code: String): List<CatalogProduct> = products.filter {
        it.barcode == code || it.internalCode.equals(code, true) || (it.reference.isNotEmpty() && it.reference.equals(code, true))
    }

    private fun resolveProduct(code: String, onResolved: (CatalogProduct?) -> Unit) {
        val found = productsFor(code)
        when (found.size) {
            0 -> AlertDialog.Builder(this).setTitle("Produto não localizado")
                .setMessage("O código $code não aparece no catálogo de $companyName. Deseja registrar mesmo assim?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Registrar sem catálogo") { _, _ -> onResolved(null) }.show()
            1 -> onResolved(found.first())
            else -> {
                val labels = found.map { "${it.description}\nCódigo: ${it.internalCode}  •  EAN: ${it.barcode}\nReferência: ${it.reference.ifBlank { "—" }}  •  Un.: ${it.unit.ifBlank { "—" }}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Código duplicado — selecione o produto")
                    .setItems(labels) { _, index -> onResolved(found[index]) }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        titleBar = TextView(this).apply {
            text = "LEITOR DE CÓDIGOS"
            textSize = 22f; setTextColor(Color.rgb(13, 71, 161)); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6)); setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(titleBar, matchWrap())

        locationText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(5))
        }
        root.addView(locationText, matchWrap())
        val locationButton = button("Definir corredor / local / prateleira")
        locationButton.setOnClickListener { askLocation() }
        root.addView(locationButton, matchWrap())

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // Tocar no centro do código força uma nova medição de foco e exposição.
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    focusAt(event.x, event.y)
                    view.performClick()
                    true
                } else true
            }
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, dp(190)))
        val scanInfo = TextView(this).apply {
            text = "Toque no código para focar. Se ficar borrado, afaste o celular e use o zoom. QR Code desativado."
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY)
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(scanInfo, matchWrap())

        val zoomButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val zoomOutButton = button("− Zoom")
        zoomOutButton.setOnClickListener { adjustZoom(false) }
        val zoomInButton = button("+ Zoom")
        zoomInButton.setOnClickListener { adjustZoom(true) }
        zoomButtons.addView(zoomOutButton, weighted()); zoomButtons.addView(zoomInButton, weighted())
        root.addView(zoomButtons)

        val cameraButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val torchButton = button("Lanterna")
        torchButton.setOnClickListener {
            torchOn = !torchOn
            try {
                camera?.cameraControl?.enableTorch(torchOn)
                torchButton.text = if (torchOn) "Desligar lanterna" else "Lanterna"
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Lanterna indisponível neste aparelho.", Toast.LENGTH_SHORT).show()
            }
        }
        val manualButton = button("Digitar código")
        manualButton.setOnClickListener { showManualEntry() }
        cameraButtons.addView(torchButton, weighted()); cameraButtons.addView(manualButton, weighted())
        root.addView(cameraButtons)

        val modeButton = button("Modo Contagem: DESATIVADO")
        modeButton.setOnClickListener {
            countMode = !countMode
            modeButton.text = if (countMode) "Modo Contagem: ATIVADO" else "Modo Contagem: DESATIVADO"
            Toast.makeText(this, if (countMode) "Será solicitada a quantidade em cada leitura." else "Cada leitura será registrada como 1 unidade.", Toast.LENGTH_SHORT).show()
        }
        root.addView(modeButton, matchWrap())

        countText = TextView(this).apply {
            textSize = 15f; setTextColor(Color.rgb(33, 33, 33)); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(countText, matchWrap())
        lastText = TextView(this).apply {
            text = "Último código: —"; textSize = 13f; setTextColor(Color.rgb(55, 71, 79)); setPadding(0, 0, 0, dp(4))
        }
        root.addView(lastText, matchWrap())

        listView = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView.adapter = adapter
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val record = records[position]
            AlertDialog.Builder(this).setTitle("Remover registro")
                .setMessage("Remover a leitura ${position + 1}: ${record.code}?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover") { _, _ -> records.removeAt(position); saveRecords(); refreshList() }
                .show()
            true
        }
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val exportButton = button("Exportar")
        exportButton.setOnClickListener { showExportOptions() }
        val clearButton = button("Limpar lista")
        clearButton.setOnClickListener { confirmClear() }
        actions.addView(exportButton, weighted()); actions.addView(clearButton, weighted())
        root.addView(actions)
        setContentView(root)
        updateLocationText()
        refreshList()
    }

    private fun askLocation(onDone: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Ex.: Corredor A - Prateleira 03"
            setText(currentLocation)
            isSingleLine = true
        }
        AlertDialog.Builder(this).setTitle("Local da leitura")
            .setMessage("Informe o corredor, local ou prateleira antes de começar.")
            .setView(input)
            .setCancelable(false)
            .setNegativeButton("Sem local") { _, _ -> currentLocation = "Não informado"; updateLocationText(); onDone?.invoke() }
            .setPositiveButton("Confirmar") { _, _ ->
                currentLocation = input.text.toString().trim().ifEmpty { "Não informado" }
                updateLocationText(); onDone?.invoke()
            }.show()
    }

    private fun updateLocationText() {
        if (::locationText.isInitialized) locationText.text = "Local: $currentLocation"
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewBuilder = Preview.Builder()
            // Solicita AF contínuo quando o dispositivo/lente oferece esse modo.
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Somente formatos de código de barras lineares; QR Code e códigos 2D ficam desativados.
            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR
            ).build()
            val scanner = BarcodeScanning.getClient(options)
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) { imageProxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image).addOnSuccessListener { barcodes ->
                    if (scanning) return@addOnSuccessListener
                    val now = System.currentTimeMillis()
                    // Escolhe somente códigos que já passam pela validação matemática/formato.
                    val barcode = barcodes.firstOrNull { item ->
                        val raw = item.rawValue?.trim()
                        !raw.isNullOrBlank() && isValidBarcode(raw, item.format)
                    }
                    val value = barcode?.rawValue?.trim()
                    if (!value.isNullOrBlank() && barcode != null) {
                        lastSeenBarcodeAt = now
                        if (value == candidateCode && barcode.format == candidateFormat) {
                            candidateHits++
                        } else {
                            candidateCode = value
                            candidateFormat = barcode.format
                            candidateHits = 1
                        }

                        // Exige 3 quadros consecutivos iguais; não registra o mesmo item
                        // repetidamente enquanto ele permanece diante da câmera.
                        val alreadyAcceptedRecently = value == lastAcceptedCode && now - lastAcceptedAt < 1500
                        if (candidateHits >= 3 && !alreadyAcceptedRecently &&
                            (value != lastAcceptedCode || now - lastAcceptedAt >= 1500)) {
                            lastAcceptedCode = value
                            lastAcceptedAt = now
                            candidateHits = 0
                            runOnUiThread { addScannedCode(value, barcode.format) }
                        }
                    } else if (now - lastSeenBarcodeAt > 900) {
                        candidateCode = ""
                        candidateFormat = Barcode.FORMAT_UNKNOWN
                        candidateHits = 0
                        lastAcceptedCode = ""
                        lastAcceptedAt = 0L
                    }
                }.addOnCompleteListener { imageProxy.close() }
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Não foi possível iniciar a câmera: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusAt(x: Float, y: Float) {
        val activeCamera = camera ?: return
        if (previewView.width == 0 || previewView.height == 0) return
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            activeCamera.cameraControl.startFocusAndMetering(action)
            Toast.makeText(this, "Focando no ponto selecionado…", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Foco por toque indisponível nesta câmera.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun adjustZoom(increase: Boolean) {
        val activeCamera = camera ?: run {
            Toast.makeText(this, "A câmera ainda não está pronta.", Toast.LENGTH_SHORT).show()
            return
        }
        val state = activeCamera.cameraInfo.zoomState.value ?: return
        val requested = if (increase) state.zoomRatio * 1.25f else state.zoomRatio / 1.25f
        val ratio = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        activeCamera.cameraControl.setZoomRatio(ratio)
    }

    private fun addScannedCode(code: String, format: Int) {
        if (!isValidBarcode(code, format)) {
            lastText.text = "Leitura rejeitada: código inválido ($code)"
            showNotice("Leitura não gravada", "O código lido é inválido ou está incompleto.\n\nCódigo: $code\n\nNenhum registro foi adicionado. Confira o produto e tente novamente.")
            return
        }
        // O bip confirma a captura pela câmera, antes de pedir a quantidade.
        playScanBeep()
        resolveProduct(code) { product ->
            val identity = product?.internalCode?.ifBlank { code } ?: code
            if (records.any { (it.internalCode.ifBlank { it.code }).equals(identity, true) }) {
                showNotice("Produto já registrado", "${product?.description ?: "O código $code"} já existe na listagem.\n\nA leitura não foi adicionada novamente.")
                return@resolveProduct
            }
            addRecordWithQuantity(code, formatName(format), product)
        }
    }

    private fun showNotice(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun playScanBeep() {
        try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) { }
    }

    private fun isValidBarcode(value: String, format: Int = Barcode.FORMAT_UNKNOWN): Boolean {
        val code = value.trim()
        if (code.isEmpty() || code.length > 80 || code.any { it.isWhitespace() }) return false
        return when {
            format == Barcode.FORMAT_EAN_13 || (format == Barcode.FORMAT_UNKNOWN && code.matches(Regex("\\d{13}"))) ->
                code.matches(Regex("\\d{13}")) && checkDigitValid(code)
            format == Barcode.FORMAT_EAN_8 || (format == Barcode.FORMAT_UNKNOWN && code.matches(Regex("\\d{8}"))) ->
                code.matches(Regex("\\d{8}")) && checkDigitValid(code)
            format == Barcode.FORMAT_UPC_A || (format == Barcode.FORMAT_UNKNOWN && code.matches(Regex("\\d{12}"))) ->
                code.matches(Regex("\\d{12}")) && checkDigitValid(code)
            format == Barcode.FORMAT_UPC_E -> code.matches(Regex("\\d{8}"))
            code.matches(Regex("\\d{13}")) || code.matches(Regex("\\d{12}")) || code.matches(Regex("\\d{8}")) -> checkDigitValid(code)
            else -> code.matches(Regex("[A-Za-z0-9\\-./+_]{2,80}"))
        }
    }

    private fun checkDigitValid(code: String): Boolean {
        if (!code.all { it.isDigit() } || code.length !in listOf(8, 12, 13)) return false
        val digits = code.map { it.digitToInt() }
        val check = digits.last()
        val body = digits.dropLast(1).reversed()
        val sum = body.mapIndexed { index, digit -> digit * if (index % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == check
    }

    private fun addRecordWithQuantity(code: String, format: String, product: CatalogProduct? = null) {
        if (countMode) {
            val quantityInput = EditText(this).apply {
                hint = "Quantidade"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                textSize = 28f
                gravity = Gravity.CENTER
                setText("1")
                selectAll()
                isSingleLine = true
            }
            val dialog = AlertDialog.Builder(this).setTitle("Quantidade do item")
                .setMessage("Código: $code")
                .setView(quantityInput)
                .setNegativeButton("Cancelar") { _, _ -> candidateCode = ""; candidateHits = 0 }
                .setPositiveButton("Registrar") { _, _ ->
                    val quantity = quantityInput.text.toString().toIntOrNull()
                    if (quantity == null || quantity < 1 || quantity > 100000) {
                        showNotice("Quantidade inválida", "Informe uma quantidade entre 1 e 100000.\n\nO produto não foi gravado.")
                        candidateCode = ""; candidateHits = 0
                    } else saveRecord(code, quantity, format, product)
                }.create()
            dialog.setOnShowListener {
                quantityInput.requestFocus()
                dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
            dialog.show()
        } else saveRecord(code, 1, format, product)
    }

    private fun saveRecord(code: String, quantity: Int, format: String, product: CatalogProduct? = null) {
        records.add(ScanRecord(code, currentLocation, quantity, format, product?.internalCode.orEmpty(), product?.description.orEmpty(), product?.unit.orEmpty()))
        saveRecords(); refreshList()
        lastText.text = "Último código: $code | Qtd.: $quantity"
    }

    private fun showManualEntry() {
        val input = EditText(this).apply { hint = "Digite ou cole o código"; inputType = android.text.InputType.TYPE_CLASS_TEXT; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Digitar código").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank() || (!isValidBarcode(value) && productsFor(value).isEmpty())) {
                    showNotice("Código inválido — não gravado", "O código informado não passou na validação nem existe no catálogo.\n\nCódigo: ${value.ifEmpty { "(vazio)" }}\n\nConfira os dígitos e tente novamente.")
                } else resolveProduct(value) { product ->
                    val identity = product?.internalCode?.ifBlank { value } ?: value
                    if (records.any { (it.internalCode.ifBlank { it.code }).equals(identity, true) })
                        showNotice("Produto já registrado", "${product?.description ?: "O código $value"} já existe na listagem.")
                    else addRecordWithQuantity(value, "Manual", product)
                }
            }.show()
    }

    private fun showExportOptions() {
        if (records.isEmpty()) { Toast.makeText(this, "Ainda não há códigos para exportar.", Toast.LENGTH_SHORT).show(); return }
        val options = arrayOf("TXT — somente códigos", "CSV para Excel — detalhes e quantidade")
        AlertDialog.Builder(this).setTitle("Como deseja exportar?").setItems(options) { _, which ->
            when (which) {
                0 -> shareFile("txt", "codigos.txt", records.joinToString("\n") { it.code })
                1 -> {
                    val rows = records.mapIndexed { index, r -> "${index + 1};${csv(r.internalCode)};${csv(r.code)};${csv(r.description)};${csv(r.unit)};${csv(r.location)};${r.quantity};${csv(r.format)}" }
                    shareFile("csv", "inventario_${companyKey}.csv", "Empresa;Nº;Código interno;Código de barras;Descrição;Unidade;Local;Quantidade;Formato\n" + rows.joinToString("\n") { "$companyName;$it" })
                }
            }
        }.show()
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun shareFile(extension: String, fileName: String, content: String) {
        try {
            val dir = File(cacheDir, "exports"); if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName); file.writeText(if (extension == "csv") "\uFEFF$content" else content, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (extension == "txt") "text/plain" else "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, fileName)
                putExtra(Intent.EXTRA_TEXT, "Lista de códigos de barras (${records.size} registros).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartilhar arquivo"))
        } catch (e: Exception) { Toast.makeText(this, "Falha ao exportar: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
    }

    private fun confirmClear() {
        if (records.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Limpar lista")
            .setMessage("Isso apagará todos os ${records.size} registros armazenados. Deseja continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar tudo") { _, _ -> records.clear(); saveRecords(); refreshList(); lastText.text = "Último código: —" }
            .show()
    }

    private fun loadRecords() {
        val jsonText = prefs.getString("records_json", null)
        if (!jsonText.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonText)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    records.add(ScanRecord(o.optString("code"), o.optString("location", "Não informado"), o.optInt("quantity", 1), o.optString("format", "Desconhecido"), o.optString("internalCode"), o.optString("description"), o.optString("unit")))
                }
                return
            } catch (_: Exception) { records.clear() }
        }
        // Migra registros da versão anterior sem apagar os códigos existentes.
        val old = prefs.getString("codes", "") ?: ""
        records.addAll(old.split("\n").filter { it.isNotEmpty() }.map { ScanRecord(it, "Não informado", 1, "Versão anterior") })
        if (records.isNotEmpty()) saveRecords()
    }

    private fun saveRecords() {
        val array = JSONArray()
        records.forEach { r -> array.put(JSONObject().put("code", r.code).put("location", r.location).put("quantity", r.quantity).put("format", r.format).put("internalCode", r.internalCode).put("description", r.description).put("unit", r.unit)) }
        prefs.edit().putString("records_json", array.toString()).apply()
    }

    private fun refreshList() {
        if (!::adapter.isInitialized) return
        adapter.clear()
        adapter.addAll(records.mapIndexed { index, r -> "${index + 1}. ${r.description.ifBlank { "Produto sem descrição" }} | Interno: ${r.internalCode.ifBlank { "—" }} | EAN: ${r.code} | Qtd.: ${r.quantity} | ${r.location}" })
        adapter.notifyDataSetChanged()
        countText.text = "${companyName.ifBlank { "Inventário" }} — Registros: ${records.size} | Unidades: ${records.sumOf { it.quantity }}"
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_EAN_13 -> "EAN-13"; Barcode.FORMAT_EAN_8 -> "EAN-8"; Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"; Barcode.FORMAT_CODE_128 -> "Code 128"; Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"; Barcode.FORMAT_ITF -> "ITF"; Barcode.FORMAT_CODABAR -> "Codabar"
        else -> "Desconhecido"
    }

    private fun button(label: String) = Button(this).apply { text = label; isAllCaps = false; textSize = 13f }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        try { toneGenerator?.release() } catch (_: Exception) { }
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
