package br.com.fmarcatti.leitorcodigobarras

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    data class ScanRecord(val code: String, val location: String, val quantity: Int, val format: String)

    private val prefs by lazy { getSharedPreferences("scanner_data", MODE_PRIVATE) }
    private val records = mutableListOf<ScanRecord>()
    private lateinit var previewView: PreviewView
    private lateinit var countText: TextView
    private lateinit var lastText: TextView
    private lateinit var locationText: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var lastCode = ""
    private var lastAnyBarcodeAt = 0L
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
        loadRecords()
        buildUi()
        askLocation {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val title = TextView(this).apply {
            text = "LEITOR DE CÓDIGOS"
            textSize = 22f; setTextColor(Color.rgb(13, 71, 161)); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6)); setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title, matchWrap())

        locationText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(5))
        }
        root.addView(locationText, matchWrap())
        val locationButton = button("Definir corredor / local / prateleira")
        locationButton.setOnClickListener { askLocation() }
        root.addView(locationButton, matchWrap())

        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(previewView, LinearLayout.LayoutParams(-1, dp(190)))
        val scanInfo = TextView(this).apply {
            text = "Aponte para o código de barras. QR Code desativado."
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY)
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(scanInfo, matchWrap())

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
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Somente formatos de código de barras lineares; QR Code e códigos 2D ficam desativados.
            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417, Barcode.FORMAT_AZTEC
            ).build()
            val scanner = BarcodeScanning.getClient(options)
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) { imageProxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image).addOnSuccessListener { barcodes ->
                    if (scanning) return@addOnSuccessListener
                    val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    val value = barcode?.rawValue?.trim()
                    val now = System.currentTimeMillis()
                    if (!value.isNullOrBlank()) {
                        if (value != lastCode && now - lastAnyBarcodeAt > 250) {
                            lastCode = value
                            runOnUiThread { addScannedCode(value, barcode?.format ?: Barcode.FORMAT_UNKNOWN) }
                        }
                        lastAnyBarcodeAt = now
                    } else if (now - lastAnyBarcodeAt > 900) lastCode = ""
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

    private fun addScannedCode(code: String, format: Int) {
        if (!isValidBarcode(code, format)) {
            lastText.text = "Leitura rejeitada: código inválido ($code)"
            Toast.makeText(this, "Código inválido ou leitura incompleta. Tente novamente.", Toast.LENGTH_SHORT).show()
            return
        }
        addRecordWithQuantity(code, formatName(format))
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

    private fun addRecordWithQuantity(code: String, format: String) {
        if (countMode) {
            val quantityInput = EditText(this).apply {
                hint = "Quantidade"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("1")
            }
            AlertDialog.Builder(this).setTitle("Quantidade do item")
                .setMessage("Código: $code")
                .setView(quantityInput)
                .setNegativeButton("Cancelar") { _, _ -> lastCode = "" }
                .setPositiveButton("Registrar") { _, _ ->
                    val quantity = quantityInput.text.toString().toIntOrNull()
                    if (quantity == null || quantity < 1 || quantity > 100000) {
                        Toast.makeText(this, "Informe uma quantidade entre 1 e 100000.", Toast.LENGTH_SHORT).show()
                        lastCode = ""
                    } else saveRecord(code, quantity, format)
                }.show()
        } else saveRecord(code, 1, format)
    }

    private fun saveRecord(code: String, quantity: Int, format: String) {
        records.add(ScanRecord(code, currentLocation, quantity, format))
        saveRecords(); refreshList()
        lastText.text = "Último código: $code | Qtd.: $quantity"
        try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) { }
    }

    private fun showManualEntry() {
        val input = EditText(this).apply { hint = "Digite ou cole o código"; inputType = android.text.InputType.TYPE_CLASS_TEXT; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Digitar código").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar") { _, _ ->
                val value = input.text.toString().trim()
                if (isValidBarcode(value)) addRecordWithQuantity(value, "Manual")
                else Toast.makeText(this, "Código inválido. Confira os dígitos e tente novamente.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showExportOptions() {
        if (records.isEmpty()) { Toast.makeText(this, "Ainda não há códigos para exportar.", Toast.LENGTH_SHORT).show(); return }
        val options = arrayOf("TXT — somente códigos", "CSV para Excel — detalhes e quantidade")
        AlertDialog.Builder(this).setTitle("Como deseja exportar?").setItems(options) { _, which ->
            when (which) {
                0 -> shareFile("txt", "codigos.txt", records.joinToString("\n") { it.code })
                1 -> {
                    val rows = records.mapIndexed { index, r -> "${index + 1};${csv(r.location)};${csv(r.code)};${r.quantity};${csv(r.format)}" }
                    shareFile("csv", "codigos_sequenciais.csv", "Nº;Local;Código;Quantidade;Formato\n" + rows.joinToString("\n"))
                }
            }
        }.show()
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun shareFile(extension: String, fileName: String, content: String) {
        try {
            val dir = File(cacheDir, "exports"); if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName); file.writeText(content, Charsets.UTF_8)
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
                    records.add(ScanRecord(o.optString("code"), o.optString("location", "Não informado"), o.optInt("quantity", 1), o.optString("format", "Desconhecido")))
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
        records.forEach { r -> array.put(JSONObject().put("code", r.code).put("location", r.location).put("quantity", r.quantity).put("format", r.format)) }
        prefs.edit().putString("records_json", array.toString()).apply()
    }

    private fun refreshList() {
        if (!::adapter.isInitialized) return
        adapter.clear()
        adapter.addAll(records.mapIndexed { index, r -> "${index + 1}. ${r.code} | Qtd.: ${r.quantity} | ${r.location}" })
        adapter.notifyDataSetChanged()
        countText.text = "Registros: ${records.size} | Unidades: ${records.sumOf { it.quantity }}"
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
