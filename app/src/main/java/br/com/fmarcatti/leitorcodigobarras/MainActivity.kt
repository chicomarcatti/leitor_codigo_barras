package br.com.fmarcatti.leitorcodigobarras

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("scanner_data", MODE_PRIVATE) }
    private val codes = mutableListOf<String>()
    private lateinit var previewView: PreviewView
    private lateinit var countText: TextView
    private lateinit var lastText: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var lastCode = ""
    private var lastCodeAt = 0L
    private var lastAnyBarcodeAt = 0L
    private var scanning = false
    private var torchOn = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Permissão da câmera necessária para ler códigos.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        loadCodes()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val title = TextView(this).apply {
            text = "LEITOR DE CÓDIGOS"
            textSize = 22f
            setTextColor(Color.rgb(13, 71, 161))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title, matchWrap())

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, dp(220)))

        val scanInfo = TextView(this).apply {
            text = "Aponte a câmera para o código. A leitura é automática."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(8))
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
        cameraButtons.addView(torchButton, weighted())
        cameraButtons.addView(manualButton, weighted())
        root.addView(cameraButtons)

        countText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(33, 33, 33))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(countText, matchWrap())

        lastText = TextView(this).apply {
            text = "Último código: —"
            textSize = 14f
            setTextColor(Color.rgb(55, 71, 79))
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(lastText, matchWrap())

        listView = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView.adapter = adapter
        listView.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle("Remover código")
                .setMessage("Deseja remover a leitura ${position + 1} (${codes[position]})?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover") { _, _ ->
                    codes.removeAt(position)
                    saveCodes()
                    refreshList()
                }.show()
            true
        }
        val listParams = LinearLayout.LayoutParams(-1, 0, 1f)
        listParams.topMargin = dp(4)
        root.addView(listView, listParams)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val exportButton = button("Exportar")
        exportButton.setOnClickListener { showExportOptions() }
        val clearButton = button("Limpar lista")
        clearButton.setOnClickListener { confirmClear() }
        actions.addView(exportButton, weighted())
        actions.addView(clearButton, weighted())
        root.addView(actions)

        setContentView(root)
        refreshList()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_CODABAR,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_AZTEC
                ).build()
            val scanner = BarcodeScanning.getClient(options)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (scanning) return@addOnSuccessListener
                        val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        val now = System.currentTimeMillis()
                        if (!value.isNullOrBlank()) {
                            // Registra uma vez por aparição. Para registrar outro item igual,
                            // retire o código do campo de visão por um instante e aponte novamente.
                            if (value != lastCode && now - lastAnyBarcodeAt > 250) {
                                lastCode = value
                                lastCodeAt = now
                                runOnUiThread { addCode(value) }
                            }
                            lastAnyBarcodeAt = now
                        } else if (now - lastAnyBarcodeAt > 900) {
                            lastCode = ""
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Não foi possível iniciar a câmera: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun addCode(code: String) {
        codes.add(code)
        saveCodes()
        refreshList()
        lastText.text = "Último código: $code"
        // Vibração curta para confirmar leitura, sem interromper a câmera.
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(45, 70))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(45)
            }
        } catch (_: Exception) {}
    }

    private fun showManualEntry() {
        val input = EditText(this).apply {
            hint = "Digite ou cole o código"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Digitar código")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) addCode(value)
                else Toast.makeText(this, "Digite um código válido.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showExportOptions() {
        if (codes.isEmpty()) {
            Toast.makeText(this, "Ainda não há códigos para exportar.", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(
            "Texto (.TXT) — somente códigos",
            "CSV para Excel — somente códigos",
            "CSV para Excel — número e código"
        )
        AlertDialog.Builder(this)
            .setTitle("Como deseja exportar?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareFile("txt", "codigos.txt", codes.joinToString("\n"))
                    1 -> {
                        val content = codes.joinToString("\n") { excelSafe(it) }
                        shareFile("csv", "codigos.csv", "Código\n$content")
                    }
                    2 -> {
                        val rows = codes.mapIndexed { index, code -> "${index + 1};${excelSafe(code)}" }
                        shareFile("csv", "codigos_sequenciais.csv", "Nº;Código\n" + rows.joinToString("\n"))
                    }
                }
            }.show()
    }

    private fun excelSafe(code: String): String {
        // Mantém zeros à esquerda quando o CSV é aberto no Excel.
        val escaped = code.replace("\"", "\"\"")
        return "=\"${escaped}\""
    }

    private fun shareFile(extension: String, fileName: String, content: String) {
        try {
            val dir = File(cacheDir, "exports")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val mime = if (extension == "txt") "text/plain" else "text/csv"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                putExtra(Intent.EXTRA_TEXT, "Lista de códigos de barras (${codes.size} registros).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartilhar arquivo"))
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao exportar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClear() {
        if (codes.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Limpar lista")
            .setMessage("Isso apagará todos os ${codes.size} códigos armazenados. Deseja continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar tudo") { _, _ ->
                codes.clear()
                saveCodes()
                refreshList()
                lastText.text = "Último código: —"
            }.show()
    }

    private fun loadCodes() {
        val stored = prefs.getString("codes", "") ?: ""
        if (stored.isNotEmpty()) codes.addAll(stored.split("\n").filter { it.isNotEmpty() })
    }

    private fun saveCodes() {
        prefs.edit().putString("codes", codes.joinToString("\n")).apply()
    }

    private fun refreshList() {
        if (!::adapter.isInitialized) return
        adapter.clear()
        adapter.addAll(codes.mapIndexed { index, code -> "${index + 1}.  $code" })
        adapter.notifyDataSetChanged()
        countText.text = "Códigos armazenados: ${codes.size}"
    }

    private fun button(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
    }

    private fun weighted() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
