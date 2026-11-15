package com.example.mathai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CaptureOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread

    // تحديث واجهة التطبيق من الخيط الرئيسي
    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var mainPanel: View? = null
    private var trueMarker: TextView? = null
    private var falseMarker: TextView? = null

    private var trueParams: WindowManager.LayoutParams? = null
    private var falseParams: WindowManager.LayoutParams? = null

    private var statusText: TextView? = null
    private var autoReadButton: Button? = null
    private var autoTapButton: Button? = null

    private var autoRead = false
    private var autoTap = false
    private var processing = false
    private var markerSize = 58
    private var lastExpression = ""

    private val autoReadTask = object : Runnable {
        override fun run() {
            if (autoRead) {
                captureAndRead()
                handler.postDelayed(this, 1800)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        handlerThread = HandlerThread("MathCaptureThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        createNotificationChannel()
        startAsForeground()
        createOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val resultCode =
            intent?.getIntExtra("resultCode", 0) ?: 0

        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    "data",
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("data")
            }

        if (data != null && projection == null) {
            startScreenCapture(resultCode, data)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "math_capture_channel",
                "مساعد الرياضيات",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "math_capture_channel")
                .setContentTitle("مساعد الرياضيات")
                .setContentText("خدمة قراءة المسائل تعمل")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("مساعد الرياضيات")
                .setContentText("خدمة قراءة المسائل تعمل")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                10,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(10, notification)
        }
    }

    private fun startScreenCapture(
        resultCode: Int,
        data: Intent
    ) {
        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                handler
            )

            virtualDisplay = projection?.createVirtualDisplay(
                "MathAssistantDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            updateStatus("تم تشغيل قراءة الشاشة")

        } catch (e: Exception) {
            updateStatus("تعذر تشغيل تصوير الشاشة: ${e.message}")
        }
    }

    private fun createOverlay() {
        createMainPanel()
        createMarkers()
    }

    private fun createMainPanel() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            background = roundedBackground(
                0xEE202B3C.toInt(),
                18
            )
        }

        val title = TextView(this).apply {
            text = "مساعد الرياضيات"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 10)
        }

        statusText = TextView(this).apply {
            text = "في انتظار قراءة السؤال"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(4, 5, 4, 8)
        }

        val readButton = makeButton("اقرأ السؤال الآن") {
            captureAndRead()
        }

        autoReadButton = makeButton(
            "القراءة التلقائية: متوقفة"
        ) {
            autoRead = !autoRead

            autoReadButton?.text =
                if (autoRead) "القراءة التلقائية: تعمل"
                else "القراءة التلقائية: متوقفة"

            handler.removeCallbacks(autoReadTask)

            if (autoRead) {
                handler.post(autoReadTask)
            }
        }

        autoTapButton = makeButton(
            "الضغط التلقائي: متوقف"
        ) {
            autoTap = !autoTap

            autoTapButton?.text =
                if (autoTap) "الضغط التلقائي: يعمل"
                else "الضغط التلقائي: متوقف"
        }

        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val smaller = makeButton("تصغير") {
            changeMarkerSize(-8)
        }

        val larger = makeButton("تكبير") {
            changeMarkerSize(8)
        }

        sizeRow.addView(
            smaller,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        sizeRow.addView(
            larger,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val closeButton = makeButton("إيقاف المساعد") {
            stopSelf()
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(readButton)
        layout.addView(autoReadButton)
        layout.addView(autoTapButton)
        layout.addView(sizeRow)
        layout.addView(closeButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 100

        mainPanel = layout

        try {
            windowManager.addView(layout, params)
        } catch (e: Exception) {
            updateStatus("اسمح بظهور التطبيق فوق التطبيقات")
        }
    }

    private fun createMarkers() {
        trueMarker = createMarker(
            "✓",
            0xFF00BCD4.toInt()
        )

        falseMarker = createMarker(
            "✕",
            0xFFE91E63.toInt()
        )

        trueParams = createMarkerParams(100, 450)
        falseParams = createMarkerParams(190, 450)

        try {
            windowManager.addView(trueMarker, trueParams)
            windowManager.addView(falseMarker, falseParams)

            trueMarker?.let {
                makeDraggable(it, trueParams!!)
            }

            falseMarker?.let {
                makeDraggable(it, falseParams!!)
            }

        } catch (e: Exception) {
            updateStatus("تعذر إظهار العلامتين")
        }
    }

    private fun createMarker(
        symbol: String,
        color: Int
    ): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBackground(color, 12)
        }
    }

    private fun createMarkerParams(
        x: Int,
        y: Int
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            markerSize,
            markerSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun makeDraggable(
        view: View,
        params: WindowManager.LayoutParams
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x =
                        startX + (event.rawX - touchX).toInt()

                    params.y =
                        startY + (event.rawY - touchY).toInt()

                    try {
                        windowManager.updateViewLayout(
                            view,
                            params
                        )
                    } catch (e: Exception) {
                    }

                    true
                }

                else -> true
            }
        }
    }

    private fun changeMarkerSize(amount: Int) {
        markerSize = (markerSize + amount).coerceIn(38, 110)

        trueParams?.let { params ->
            params.width = markerSize
            params.height = markerSize

            trueMarker?.let { view ->
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                }
            }
        }

        falseParams?.let { params ->
            params.width = markerSize
            params.height = markerSize

            falseMarker?.let { view ->
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener {
                action()
            }
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun captureAndRead() {
        if (processing) return

        val reader = imageReader

        if (reader == null) {
            updateStatus("تصوير الشاشة غير جاهز")
            return
        }

        processing = true

        handler.postDelayed({
            var image: android.media.Image? = null
            var bitmap: Bitmap? = null

            try {
                image = reader.acquireLatestImage()

                if (image == null) {
                    processing = false
                    updateStatus("لم تصل صورة الشاشة، حاول مرة أخرى")
                    return@postDelayed
                }

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride

                val width = image.width
                val height = image.height

                val rowPadding =
                    rowStride - pixelStride * width

                val paddedWidth =
                    width + rowPadding / pixelStride

                val paddedBitmap = Bitmap.createBitmap(
                    paddedWidth,
                    height,
                    Bitmap.Config.ARGB_8888
                )

                paddedBitmap.copyPixelsFromBuffer(buffer)

                bitmap = Bitmap.createBitmap(
                    paddedBitmap,
                    0,
                    0,
                    width,
                    height
                )

                paddedBitmap.recycle()
                image.close()
                image = null

                recognizeBitmap(bitmap!!)

            } catch (e: Exception) {
                image?.close()
                bitmap?.recycle()
                processing = false
                updateStatus("حدث خطأ أثناء التقاط الشاشة: ${e.message}")
            }

        }, 250)
    }

    private fun recognizeBitmap(bitmap: Bitmap) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val expression = extractExpression(result.text)

                    if (expression == null) {
                        updateStatus("لم أجد مسألة مقارنة واضحة")
                    } else {
                        solveExpression(expression)
                    }
                }
                .addOnFailureListener {
                    updateStatus("تعذرت قراءة السؤال")
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    processing = false
                }

        } catch (e: Exception) {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            processing = false
            updateStatus("حدث خطأ في التعرف على النص")
        }
    }

    private fun extractExpression(text: String): String? {
        val normalized = text
            .replace('×', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace('–', '-')
            .replace('٠', '0')
            .replace('١', '1')
            .replace('٢', '2')
            .replace('٣', '3')
            .replace('٤', '4')
            .replace('٥', '5')
            .replace('٦', '6')
            .replace('٧', '7')
            .replace('٨', '8')
            .replace('٩', '9')
            .replace('۰', '0')
            .replace('۱', '1')
            .replace('۲', '2')
            .replace('۳', '3')
            .replace('۴', '4')
            .replace('۵', '5')
            .replace('۶', '6')
            .replace('۷', '7')
            .replace('۸', '8')
            .replace('۹', '9')

        val pattern = Regex(
            """[0-9().+\-*/,\s]{1,35}(?:>=|<=|>|<|==|=)[0-9().+\-*/,\s]{1,35}"""
        )

        return pattern.find(normalized)
            ?.value
            ?.replace(",", ".")
            ?.trim()
    }

    private fun solveExpression(expression: String) {
        val answer = try {
            MathSolver.solve(expression)
        } catch (e: Exception) {
            null
        }

        if (answer == null) {
            updateStatus("لم أستطع حل: $expression")
            return
        }

        if (expression == lastExpression) {
            updateStatus(
                "$expression\n" +
                        if (answer) "الإجابة صحيحة ✓"
                        else "الإجابة خاطئة ✕"
            )
            return
        }

        lastExpression = expression

        if (answer) {
            updateStatus("$expression\nالإجابة صحيحة ✓")

            if (autoTap) {
                tapMarker(trueMarker)
            }
        } else {
            updateStatus("$expression\nالإجابة خاطئة ✕")

            if (autoTap) {
                tapMarker(falseMarker)
            }
        }
    }

    private fun tapMarker(marker: TextView?) {
        if (marker == null) return

        val location = IntArray(2)
        marker.getLocationOnScreen(location)

        val centerX = location[0] + marker.width / 2f
        val centerY = location[1] + marker.height / 2f

        val accessibility =
            AutoTapAccessibilityService.instance

        if (accessibility == null) {
            updateStatus("فعّل خدمة الضغط من إعدادات إمكانية الوصول")
            return
        }

        accessibility.tap(centerX, centerY)
        updateStatus("تم إرسال أمر الضغط")
    }

    // التعديل الأساسي: تحديث الواجهة على الخيط الرئيسي
    private fun updateStatus(message: String) {
        mainHandler.post {
            statusText?.text = message
        }
    }

    override fun onDestroy() {
        autoRead = false

        if (::handler.isInitialized) {
            handler.removeCallbacksAndMessages(null)
        }

        mainHandler.removeCallbacksAndMessages(null)

        try {
            mainPanel?.let {
                windowManager.removeView(it)
            }

            trueMarker?.let {
                windowManager.removeView(it)
            }

            falseMarker?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
        }

        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        recognizer.close()

        if (::handlerThread.isInitialized) {
            handlerThread.quitSafely()
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
