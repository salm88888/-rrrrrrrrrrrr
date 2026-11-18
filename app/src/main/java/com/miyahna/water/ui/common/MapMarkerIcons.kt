package com.miyahna.water.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.miyahna.water.domain.Customer
import com.miyahna.water.domain.Order
import com.miyahna.water.domain.OrderStatus
import com.miyahna.water.domain.StopStatus
import com.miyahna.water.domain.TripCustomerStop
import com.miyahna.water.domain.TripGPSPoint
import com.miyahna.water.domain.TruckStatus
import kotlin.math.abs
import kotlin.math.roundToInt

object MapMarkerIcons {
    private val bitmapCache = object : LruCache<String, Bitmap>(64) {}

    fun activeOrdersFrom(orders: List<Order>): List<Order> =
        orders.filter { it.status != OrderStatus.DELIVERED && it.status != OrderStatus.CANCELLED }

    fun activeCustomerIds(
        orders: List<Order>,
        plannedStops: List<TripCustomerStop>
    ): Set<String> {
        val fromOrders = activeOrdersFrom(orders)
            .mapNotNull { it.customerId.takeIf(String::isNotBlank) }
        val fromStops = plannedStops
            .filter { it.status != StopStatus.DELIVERED }
            .mapNotNull { it.customerId.takeIf(String::isNotBlank) }
        return (fromOrders + fromStops).toSet()
    }

    fun isCustomerOrdering(
        customer: Customer,
        activeCustomerIds: Set<String>,
        activeOrders: List<Order>,
        plannedStops: List<TripCustomerStop>
    ): Boolean {
        if (customer.id.isNotBlank() && customer.id in activeCustomerIds) return true
        val normPhone = customer.phone.trim()
        val normName = customer.name.trim()
        val matchesOrder = activeOrders.any { o ->
            (normPhone.isNotEmpty() && o.phone.trim() == normPhone) ||
                (normName.isNotEmpty() && o.customerName.trim() == normName) ||
                (o.lat.isFinite() && o.lng.isFinite() &&
                    abs(o.lat - customer.lat) < 0.00015 && abs(o.lng - customer.lng) < 0.00015)
        }
        if (matchesOrder) return true
        return plannedStops.any { stop ->
            stop.status != StopStatus.DELIVERED && (
                (normName.isNotEmpty() && stop.customerName.trim() == normName) ||
                    (stop.lat.isFinite() && stop.lng.isFinite() &&
                        abs(stop.lat - customer.lat) < 0.00015 && abs(stop.lng - customer.lng) < 0.00015)
                )
        }
    }

    fun factoryDrawable(context: Context): Drawable =
        BitmapDrawable(context.resources, factoryBitmap(context))

    fun tankerTruckDrawable(
        context: Context,
        status: TruckStatus,
        isSelected: Boolean
    ): Drawable = BitmapDrawable(context.resources, tankerTruckBitmap(context, status, isSelected))

    fun customerTankDrawable(
        context: Context,
        isOrdering: Boolean
    ): Drawable = BitmapDrawable(context.resources, customerTankBitmap(context, isOrdering))

    fun stopPinDrawable(
        context: Context,
        sequence: Int,
        delivered: Boolean
    ): Drawable = BitmapDrawable(context.resources, stopPinBitmap(context, sequence, delivered))

    fun detectedStopDrawable(
        context: Context,
        unplanned: Boolean
    ): Drawable = BitmapDrawable(context.resources, detectedStopBitmap(context, unplanned))

    fun gpsOffPinDrawable(context: Context): Drawable =
        BitmapDrawable(context.resources, gpsOffPinBitmap(context))

    fun speedCheckpointDrawable(context: Context, speedKmH: Int): Drawable =
        BitmapDrawable(context.resources, speedCheckpointBitmap(context, speedKmH))

    fun pickedPinDrawable(context: Context): Drawable =
        BitmapDrawable(context.resources, pickedPinBitmap(context))

    fun gpsGapReasonAr(reason: String): String = when (reason.trim().lowercase()) {
        "gps_hardware_disabled", "device_gps_off" -> "إيقاف GPS من هاتف السائق"
        "weak_signal" -> "ضعف إشارة GPS"
        "gps_paused" -> "توقف مؤقت / انقطاع إشارة GPS"
        else -> reason.trim().ifBlank { "انقطاع أو إيقاف GPS" }
    }

    fun sampleSpeedCheckpoints(
        points: List<TripGPSPoint>,
        maxCheckpoints: Int = 10
    ): List<TripGPSPoint> {
        val valid = points.filter {
            it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0
        }
        if (valid.size <= maxCheckpoints) return valid
        val picked = linkedSetOf<Int>()
        picked.add(0)
        picked.add(valid.lastIndex)
        val maxSpeedIdx = valid.indices.maxByOrNull {
            valid[it].speedKmH.takeIf { sp -> sp.isFinite() } ?: -1.0
        }
        if (maxSpeedIdx != null) picked.add(maxSpeedIdx)
        val step = (valid.size - 1).toDouble() / (maxCheckpoints - 1).coerceAtLeast(1)
        for (k in 0 until maxCheckpoints) {
            val idx = (k * step).roundToInt().coerceIn(0, valid.lastIndex)
            picked.add(idx)
        }
        return picked.sorted().take(maxCheckpoints).map { valid[it] }
    }

    fun factoryBitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val key = "factory_${density}"
        bitmapCache.get(key)?.let { return it }

        val size = (48f * density).toInt().coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.44f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Outer halo
        paint.color = Color.argb(65, 56, 189, 248)
        canvas.drawCircle(cx, cy, size * 0.49f, paint)

        // Deep sky badge background
        paint.color = Color.parseColor("#0284C7")
        canvas.drawCircle(cx, cy, r, paint)

        // Crisp white ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, paint)

        // Factory silhouette (chimneys + sawtooth roof + base)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val w = size.toFloat()

        // Two chimneys
        canvas.drawRoundRect(
            RectF(w * 0.62f, w * 0.21f, w * 0.70f, w * 0.46f),
            1.5f * density, 1.5f * density, paint
        )
        canvas.drawRoundRect(
            RectF(w * 0.73f, w * 0.25f, w * 0.80f, w * 0.46f),
            1.5f * density, 1.5f * density, paint
        )

        // Building sawtooth body
        val factoryPath = Path().apply {
            moveTo(w * 0.22f, w * 0.73f)
            lineTo(w * 0.22f, w * 0.44f)
            lineTo(w * 0.36f, w * 0.35f)
            lineTo(w * 0.36f, w * 0.46f)
            lineTo(w * 0.50f, w * 0.35f)
            lineTo(w * 0.50f, w * 0.46f)
            lineTo(w * 0.62f, w * 0.39f)
            lineTo(w * 0.62f, w * 0.45f)
            lineTo(w * 0.78f, w * 0.45f)
            lineTo(w * 0.78f, w * 0.73f)
            close()
        }
        canvas.drawPath(factoryPath, paint)

        // Doorway & water droplet detail inside factory
        paint.color = Color.parseColor("#0284C7")
        canvas.drawRoundRect(
            RectF(w * 0.45f, w * 0.57f, w * 0.56f, w * 0.73f),
            2f * density, 2f * density, paint
        )
        canvas.drawRect(w * 0.29f, w * 0.52f, w * 0.37f, w * 0.59f, paint)
        canvas.drawRect(w * 0.63f, w * 0.52f, w * 0.71f, w * 0.59f, paint)

        bitmapCache.put(key, bmp)
        return bmp
    }

    fun truckStatusColor(status: TruckStatus): Int = when (status) {
        TruckStatus.ON_TRIP -> Color.parseColor("#0284C7")
        TruckStatus.LOADING -> Color.parseColor("#D97706")
        TruckStatus.IDLE -> Color.parseColor("#059669")
        TruckStatus.MAINTENANCE -> Color.parseColor("#E11D48")
    }

    fun customerTankFillColor(isOrdering: Boolean): Int =
        if (isOrdering) Color.parseColor("#EF4444") else Color.parseColor("#94A3B8")

    fun tankerTruckBitmap(
        context: Context,
        status: TruckStatus,
        isSelected: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val key = "truck_${status.name}_${isSelected}_$density"
        bitmapCache.get(key)?.let { return it }

        val size = ((if (isSelected) 54f else 46f) * density).toInt().coerceAtLeast(46)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.42f

        val fillColor = truckStatusColor(status)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Outer glow
        paint.color = Color.argb(if (isSelected) 110 else 65, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor))
        canvas.drawCircle(cx, cy, size * 0.48f, paint)

        // Badge background
        paint.color = fillColor
        canvas.drawCircle(cx, cy, r, paint)

        // White border (thicker when selected)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (isSelected) 3.2f else 2.2f) * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, paint)

        // Side-view Water Tanker Truck (الوايت)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val w = size.toFloat()

        // Cylindrical tank body (rear left)
        canvas.drawRoundRect(
            RectF(w * 0.19f, w * 0.34f, w * 0.59f, w * 0.59f),
            w * 0.08f, w * 0.08f, paint
        )
        // Tank top cap
        canvas.drawRoundRect(
            RectF(w * 0.33f, w * 0.30f, w * 0.45f, w * 0.35f),
            1.5f * density, 1.5f * density, paint
        )
        // Chassis bar
        canvas.drawRect(w * 0.19f, w * 0.59f, w * 0.80f, w * 0.64f, paint)
        // Truck cab (front right)
        val cabPath = Path().apply {
            moveTo(w * 0.61f, w * 0.60f)
            lineTo(w * 0.61f, w * 0.37f)
            lineTo(w * 0.72f, w * 0.37f)
            lineTo(w * 0.80f, w * 0.48f)
            lineTo(w * 0.80f, w * 0.60f)
            close()
        }
        canvas.drawPath(cabPath, paint)

        // Cab windshield
        paint.color = fillColor
        val windshield = Path().apply {
            moveTo(w * 0.64f, w * 0.40f)
            lineTo(w * 0.71f, w * 0.40f)
            lineTo(w * 0.76f, w * 0.47f)
            lineTo(w * 0.64f, w * 0.47f)
            close()
        }
        canvas.drawPath(windshield, paint)

        // Water stripe on tank
        canvas.drawRoundRect(
            RectF(w * 0.24f, w * 0.45f, w * 0.54f, w * 0.50f),
            2f * density, 2f * density, paint
        )

        // Two dark wheels + white hubs
        val wheelY = w * 0.66f
        val wheelR = w * 0.075f
        paint.color = Color.parseColor("#0F172A")
        canvas.drawCircle(w * 0.31f, wheelY, wheelR, paint)
        canvas.drawCircle(w * 0.70f, wheelY, wheelR, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(w * 0.31f, wheelY, wheelR * 0.42f, paint)
        canvas.drawCircle(w * 0.70f, wheelY, wheelR * 0.42f, paint)

        bitmapCache.put(key, bmp)
        return bmp
    }

    fun customerTankBitmap(
        context: Context,
        isOrdering: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val key = "tank_${isOrdering}_$density"
        bitmapCache.get(key)?.let { return it }

        val size = ((if (isOrdering) 38f else 30f) * density).toInt().coerceAtLeast(30)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = size.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val tankFill = customerTankFillColor(isOrdering)
        val tankStroke = if (isOrdering) Color.parseColor("#7F1D1D") else Color.parseColor("#334155")
        val ribColor = if (isOrdering) Color.parseColor("#FCA5A5") else Color.parseColor("#CBD5E1")

        if (isOrdering) {
            // Subtle red halo so ordering customers pop on the map
            paint.color = Color.argb(72, 239, 68, 68)
            canvas.drawCircle(w / 2f, w / 2f, w * 0.48f, paint)
        }

        // White backing pill so the tank stands out over both street and satellite imagery
        paint.color = Color.argb(235, 255, 255, 255)
        canvas.drawRoundRect(
            RectF(w * 0.18f, w * 0.12f, w * 0.82f, w * 0.88f),
            w * 0.14f, w * 0.14f, paint
        )

        // Top hatch cap
        paint.style = Paint.Style.FILL
        paint.color = tankStroke
        canvas.drawRoundRect(
            RectF(w * 0.40f, w * 0.16f, w * 0.60f, w * 0.25f),
            2f * density, 2f * density, paint
        )

        // Tank body
        val tankRect = RectF(w * 0.24f, w * 0.23f, w * 0.76f, w * 0.76f)
        paint.color = tankFill
        canvas.drawRoundRect(tankRect, w * 0.10f, w * 0.10f, paint)

        // Horizontal ribs on water tank
        paint.color = ribColor
        canvas.drawRect(w * 0.26f, w * 0.39f, w * 0.74f, w * 0.43f, paint)
        canvas.drawRect(w * 0.26f, w * 0.56f, w * 0.74f, w * 0.60f, paint)

        // Dark outline around tank
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (isOrdering) 2.0f else 1.6f) * density
        paint.color = tankStroke
        canvas.drawRoundRect(tankRect, w * 0.10f, w * 0.10f, paint)

        // Support legs
        paint.style = Paint.Style.FILL
        paint.color = tankStroke
        canvas.drawRect(w * 0.30f, w * 0.76f, w * 0.38f, w * 0.84f, paint)
        canvas.drawRect(w * 0.62f, w * 0.76f, w * 0.70f, w * 0.84f, paint)

        bitmapCache.put(key, bmp)
        return bmp
    }

    fun stopPinBitmap(
        context: Context,
        sequence: Int,
        delivered: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val key = "stop_${sequence}_${delivered}_$density"
        bitmapCache.get(key)?.let { return it }

        val size = (30f * density).toInt().coerceAtLeast(30)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.42f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (delivered) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        val yPos = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(sequence.toString(), cx, yPos, paint)

        bitmapCache.put(key, bmp)
        return bmp
    }

    fun detectedStopBitmap(context: Context, unplanned: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val key = "det_${unplanned}_$density"
        bitmapCache.get(key)?.let { return it }

        val size = (26f * density).toInt().coerceAtLeast(26)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val color = if (unplanned) Color.parseColor("#F43F5E") else Color.parseColor("#34D399")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(cx, cy, size * 0.46f, paint)
        paint.color = color
        canvas.drawCircle(cx, cy, size * 0.28f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, size * 0.28f, paint)

        bitmapCache.put(key, bmp)
        return bmp
    }

    fun speedCheckpointBitmap(context: Context, speedKmH: Int): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val clamped = speedKmH.coerceIn(0, 199)
        val key = "spd_${clamped}_$density"
        bitmapCache.get(key)?.let { return it }

        val size = (28f * density).toInt().coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.42f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#0284C7")
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (clamped >= 100) 8.5f * density else 10f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        val yPos = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(clamped.toString(), cx, yPos, paint)

        bitmapCache.put(key, bmp)
        return bmp
    }

    fun gpsOffPinBitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val key = "gpsoff_$density"
        bitmapCache.get(key)?.let { return it }

        val size = (34f * density).toInt().coerceAtLeast(34)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.40f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(90, 225, 29, 72)
        canvas.drawCircle(cx, cy, size * 0.48f, paint)

        paint.color = Color.parseColor("#DC2626")
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 14f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        val yPos = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("!", cx, yPos, paint)

        bitmapCache.put(key, bmp)
        return bmp
    }

    fun pickedPinBitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val key = "picked_$density"
        bitmapCache.get(key)?.let { return it }

        val size = (36f * density).toInt().coerceAtLeast(36)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(75, 225, 29, 72)
        canvas.drawCircle(cx, cy, size * 0.46f, paint)
        paint.color = Color.parseColor("#E11D48")
        canvas.drawCircle(cx, cy, size * 0.28f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, size * 0.28f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, size * 0.10f, paint)

        bitmapCache.put(key, bmp)
        return bmp
    }
}
